// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchesGitAction
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val TAG = "DeleteRemoteBranchesGitAction"

/** A push names at most this many branches, twice each: the branch and its lease. */
private const val MAX_BRANCHES_PER_PUSH = 100

/**
 * A push's branch names and leases take at most this many characters, which leaves room for git's path and Leaf's own
 * arguments under Windows' 32,767 characters of command line.
 */
private const val MAX_ARGUMENT_CHARS = 24_000

/**
 * Deletes several of a remote's branches on the server (fork-only, follows upstream issue 116) with
 * `git push --porcelain <remote> --delete <branch>…`, through [GitCliRemoteCommand], so Leaf's credential dialogs,
 * the user's ssh and credential helpers, and Cancel apply as for a push.
 *
 * Each branch goes with `--force-with-lease=<branch>:<tip>`, the commit that the dialog showed, so the server deletes
 * it only if it still has that commit: one that someone pushed to, or deleted, since the last fetch comes back as
 * `stale info`, and stays. The server is then asked once what it has ([serverTips]): a branch that's gone counts as
 * deleted, and one that someone pushed to says its new commit, which deleting it anyway takes as its lease. git reports
 * each branch on its own line, so one that the server refuses, such as a
 * protected branch, never stops the others. A server's `pre-receive` hook sees all of a push's branches at once, and
 * refusing one refuses them all.
 *
 * git removes the remote-tracking branches of what it deleted, when the remote's fetch refspec maps them, which a
 * single-branch clone's doesn't; this removes those that are left.
 */
class DeleteRemoteBranchesGitAction @Inject constructor(
    private val jgit: JGit,
    private val backend: RemoteOperationsBackend,
    private val remoteCommand: GitCliRemoteCommand,
) : IDeleteRemoteBranchesGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        remote: String,
        branches: List<BranchToDelete>,
    ): Either<BranchesDeletion, GitError> = either {
        backend.whyNotGitCli()?.let { raiseError(RemoteBranchesNeedGitError(it)) }

        val prepared = jgit.provide(repositoryPath) { git ->
            prepare(git.repository, remote, branches.distinctBy { it.name })
        }.bind()

        val outcomes = mutableMapOf<String, BranchDeletionOutcome>()
        outcomes.putAll(prepared.kept.associateBy { it.branch.name })

        val remoteMessages = mutableListOf<String>()
        // How many push URLs reported each branch: git ls-remote asks only the fetch URL
        val reports = mutableMapOf<String, Int>()
        var reachedServer = false
        var failure: String? = null

        for (chunk in chunked(prepared.toPush)) {
            val stop = failure

            if (stop != null) {
                // The push before failed before the server, as this one would: authentication, the connection
                chunk.forEach { outcomes[it.branch.name] = kept(it.branch, BranchKept.Failed(stop)) }
                continue
            }

            val run = remoteCommand.run(
                prepared.directory,
                pushArguments(remote, chunk),
                authenticated = ::pushReachedRemote,
                // The dialog shows that it's deleting, not the tab's processing screen, as no task runs
                onProgress = {},
            )

            // git didn't run, or the user closed one of its questions: an error while nothing is deleted yet, and
            // after an earlier push deleted some, the branches left say why
            val output = when {
                run is Either.Ok -> run.value
                !reachedServer -> run.bind()
                else -> {
                    val message = (run as Either.Err).error.let { (it as? RemoteOperationError)?.output ?: it.toString() }
                    failure = message
                    chunk.forEach { outcomes[it.branch.name] = kept(it.branch, BranchKept.Failed(message)) }
                    continue
                }
            }

            // A remote with several push URLs reports each branch once for each
            val refs = parsePushPorcelain(output.stdout).groupBy { it.destination }
            remoteMessages += remoteLines(output.stderr)

            if (refs.isEmpty()) {
                if (!reachedServer) {
                    // Nothing was deleted: the dialog shows why, and the user can try again
                    raiseError(remoteOperationError(output.exitCode, output.stderr))
                }

                val message = readableGitOutput(output.stderr)
                failure = message
                chunk.forEach { outcomes[it.branch.name] = kept(it.branch, BranchKept.Failed(message)) }
                continue
            }

            reachedServer = true

            for (pushed in chunk) {
                outcomes[pushed.branch.name] = outcomeOf(pushed.branch, refs[pushed.serverRef].orEmpty())
                reports[pushed.branch.name] = refs[pushed.serverRef].orEmpty().size
            }
        }

        // The lease refused them: already gone, or someone pushed to them, which the server says. A remote with several
        // push URLs keeps them as they are, as the server asked would be only one of them
        val stale = prepared.toPush.filter {
            reports[it.branch.name] == 1 &&
                (outcomes[it.branch.name] as? BranchDeletionOutcome.Kept)?.reason is BranchKept.ChangedOnRemote
        }

        if (stale.isNotEmpty()) {
            when (val tips = remoteCommand.serverTips(prepared.directory, remote, stale.map { it.serverRef }, onProgress = {})) {
                is Either.Err -> printError(TAG, "Could not ask $remote what it has now: ${tips.error}")
                is Either.Ok -> for (pushed in stale) {
                    val now = tips.value[pushed.serverRef]

                    outcomes[pushed.branch.name] = if (now == null) {
                        BranchDeletionOutcome.Deleted(pushed.branch)
                    } else {
                        kept(pushed.branch, BranchKept.ChangedOnRemote(now))
                    }
                }
            }
        }

        val deleted = outcomes.values.filterIsInstance<BranchDeletionOutcome.Deleted>().map { it.branch }

        // The server deleted them, which is what the result must say, whatever happens to the local copies
        jgit.provide(repositoryPath) { git -> removeTrackingBranches(git.repository, deleted) }
            .onErr { printError(TAG, "Could not remove the remote-tracking branches: $it") }

        Either.Ok(
            BranchesDeletion(
                outcomes = branches.distinctBy { it.name }.mapNotNull { outcomes[it.name] },
                remoteMessage = remoteMessages.distinct().joinToString("\n").ifBlank { null },
            )
        )
    }

    /** The branches that can go to the server, with their names there, and those that stay before git runs. */
    private fun prepare(repository: Repository, remote: String, branches: List<BranchToDelete>): Prepared {
        val prefix = "${Constants.R_REMOTES}$remote/"
        val toPush = mutableListOf<PushedBranch>()
        val stay = mutableListOf<BranchDeletionOutcome.Kept>()

        for (branch in branches) {
            val ref = repository.exactRef(branch.name)

            when {
                !branch.name.startsWith(prefix) ->
                    stay += kept(branch, BranchKept.Failed("${branch.name} isn't a branch of $remote."))

                ref == null -> stay += kept(branch, BranchKept.NoLongerExists)
                ref.isSymbolic -> stay += kept(branch, BranchKept.RemoteHead)
                ref.objectId?.name != branch.tip -> stay += kept(branch, BranchKept.Moved(ref.objectId?.name.orEmpty()))

                else -> toPush += PushedBranch(branch, repository.serverRefOf(remote, branch.name))
            }
        }

        return Prepared(repository.commandDirectory(), toPush, stay)
    }

    /** What git reported for [branch], once for each push URL: deleted only when every URL deleted it. */
    private fun outcomeOf(branch: BranchToDelete, refs: List<PushedRef>): BranchDeletionOutcome {
        val ref = refs.firstOrNull { it.flag != '-' }

        return when {
            refs.isEmpty() -> kept(branch, BranchKept.Failed("git didn't report this branch."))
            ref == null -> BranchDeletionOutcome.Deleted(branch)
            ref.reason == "stale info" -> kept(branch, BranchKept.ChangedOnRemote(now = null))
            ref.isRejected -> kept(branch, BranchKept.RefusedByRemote(ref.reason ?: ref.summary.trim('[', ']')))
            else -> kept(branch, BranchKept.Failed(ref.summary))
        }
    }

    private fun kept(branch: BranchToDelete, reason: BranchKept) = BranchDeletionOutcome.Kept(branch, reason)

    private class Prepared(
        val directory: File,
        val toPush: List<PushedBranch>,
        val kept: List<BranchDeletionOutcome.Kept>,
    )
}

/**
 * Removes the remote-tracking branches that git left of the [deleted] branches, each only while it still points to the
 * commit that was deleted.
 */
internal fun removeTrackingBranches(repository: Repository, deleted: List<BranchToDelete>) {
    for (branch in deleted) {
        val ref = repository.exactRef(branch.name) ?: continue
        val tip = ObjectId.fromString(branch.tip)

        if (ref.objectId != tip) continue

        try {
            val update = repository.updateRef(branch.name)

            update.setExpectedOldObjectId(tip)
            update.isForceUpdate = true

            val result = update.delete()

            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
                printError(TAG, "Could not remove ${branch.name}: $result")
            }
        } catch (e: IOException) {
            // The branch is gone from the server, which is what matters; the next fetch with prune removes it
            printError(TAG, "Could not remove ${branch.name}: ${e.message}")
        }
    }
}

/** A branch to delete and its full name on the server, `refs/heads/x`. */
internal class PushedBranch(val branch: BranchToDelete, val serverRef: String)

/** `git push`'s arguments for deleting [branches] on [remote], each with its lease. */
internal fun pushArguments(remote: String, branches: List<PushedBranch>): List<String> = buildList {
    addAll(listOf("push", "--porcelain", "--progress"))
    branches.forEach { add("--force-with-lease=${it.serverRef}:${it.branch.leaseTip}") }
    addAll(listOf(remote, "--delete"))
    branches.forEach { add(it.serverRef) }
}

/** [branches] in pushes of at most [MAX_BRANCHES_PER_PUSH], whose arguments stay under [MAX_ARGUMENT_CHARS]. */
internal fun chunked(branches: List<PushedBranch>): List<List<PushedBranch>> {
    val chunks = mutableListOf<List<PushedBranch>>()
    var current = mutableListOf<PushedBranch>()
    var chars = 0

    for (branch in branches) {
        // The lease names the branch and its tip, then the branch is named again
        val size = branch.serverRef.length * 2 + branch.branch.leaseTip.length + 24

        if (current.isNotEmpty() && (current.size >= MAX_BRANCHES_PER_PUSH || chars + size > MAX_ARGUMENT_CHARS)) {
            chunks += current
            current = mutableListOf()
            chars = 0
        }

        current += branch
        chars += size
    }

    if (current.isNotEmpty()) chunks += current

    return chunks
}

/** What the server said: git's `remote:` lines, without that prefix and the blank ones. */
internal fun remoteLines(stderr: String): List<String> =
    stderr.lineSequence()
        .map { it.trimEnd('\r', ' ') }
        .filter { it.startsWith("remote:") }
        .map { it.removePrefix("remote:").trim() }
        .filter { it.isNotEmpty() }
        .toList()
