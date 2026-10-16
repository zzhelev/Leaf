// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.worktrees.unmergedPaths
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.interfaces.IAbortSquashMergeGitAction
import dev.app.leaf.domain.interfaces.IPutBackMergeAutostashGitAction
import dev.app.leaf.domain.models.AutostashNotice
import dev.app.leaf.domain.models.AutostashOperation
import dev.app.leaf.domain.models.AutostashPutBack
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration

private const val TAG = "Autostashes"

/** Where `git merge` keeps the changes that `merge.autoStash` set aside until the merge ends, one per worktree. */
private const val MERGE_AUTOSTASH = "MERGE_AUTOSTASH"

/** The message of the stash entry that git makes of an autostash that it couldn't put back, and that Leaf makes. */
internal const val AUTOSTASH_MESSAGE = "autostash"

/** The stash list's newest entry: its commit, and its message (`%gs`). */
internal data class NewestStash(val id: String, val message: String)

/**
 * The changes that git sets aside before a merge or a rebase (`merge.autoStash`, `rebase.autoStash`), and puts back
 * once it ends (fork-only). When they don't apply, they go to the stash list as "autostash", and the tab shows an
 * [AutostashNotice].
 *
 * git puts a rebase's back itself, as Leaf's Continue, Skip and Abort run git for a rebase that git started. A merge's
 * wait in `MERGE_AUTOSTASH` until the merge is committed or aborted, which Leaf does itself (JGit's commit, JGit's hard
 * reset, `git reset --merge`), so Leaf puts them back as git would. Otherwise the next `git commit` in a terminal puts
 * them back, maybe days later, and `git reset --hard` or the next `git merge` stores them (git 2.54).
 *
 * Leaf asks git for `MERGE_AUTOSTASH`, never for its file: with the reftable backend, it's in the reftable.
 */
class Autostashes @Inject constructor(
    private val gitCli: GitCli,
    private val repositoryStateRepository: RepositoryStateRepository,
) {
    /** The stash list's newest entry in [workTree]'s repository, or null when there's none, or git can't tell. */
    internal suspend fun newestStash(workTree: File): NewestStash? {
        val output = gitCli
            .run(workTree, listOf("log", "-g", "-1", "--no-show-signature", "--format=%H%x00%gs", "refs/stash"))
            .okOrNull()
            ?: return null
        val parts = output.trimEnd('\n').split('\u0000', limit = 2)

        return if (parts.size == 2) NewestStash(parts[0], parts[1]) else null
    }

    /**
     * Shows a notice when git kept an autostash in the stash list as it ended a merge or a rebase: the stash list's
     * newest entry isn't [before] any more, and has git's message. git does that, and exits with 0, when the changes
     * that it set aside don't apply (`apply_save_autostash_oid`).
     */
    internal suspend fun noticeIfKept(
        workTree: File,
        before: NewestStash?,
        setAsideFor: AutostashOperation,
        after: AutostashPutBackAfter,
    ) {
        val newest = newestStash(workTree) ?: return

        if (newest.id != before?.id && newest.message == AUTOSTASH_MESSAGE) {
            notice(workTree, setAsideFor, after)
        }
    }

    /** The commit in `MERGE_AUTOSTASH`, the changes that `merge.autoStash` set aside in [workTree], or null. */
    internal suspend fun mergeAutostash(workTree: File): Either<String?, GitError> = either {
        val args = listOf("rev-parse", "-q", "--verify", MERGE_AUTOSTASH)
        val output = gitCli.execute(workTree, args).bind()

        when (output.exitCode) {
            0 -> Either.Ok(output.stdout.trim())
            // -q --verify exits with 1, and says nothing, when there's no such ref
            1 -> Either.Ok(null)
            else -> Either.Err(
                GitCliError.CommandFailed("git " + args.joinToString(" "), output.exitCode, output.stderr.trim())
            )
        }
    }

    internal suspend fun deleteMergeAutostash(workTree: File, id: String): Either<Unit, GitError> {
        return gitCli.run(workTree, listOf("update-ref", "-d", MERGE_AUTOSTASH, id)).mapOk { }
    }

    internal suspend fun restoreMergeAutostash(workTree: File, id: String): Either<Unit, GitError> {
        return gitCli.run(workTree, listOf("update-ref", MERGE_AUTOSTASH, id)).mapOk { }
    }

    /**
     * Puts the changes in the stash commit [id] back in [workTree], as git's `apply_save_autostash_oid` does:
     * `git stash apply`, or when that fails, `git stash store -m autostash`, with a notice. It leaves `MERGE_AUTOSTASH`
     * as it is.
     */
    internal suspend fun putBack(
        workTree: File,
        id: String,
        setAsideFor: AutostashOperation,
        after: AutostashPutBackAfter,
    ): Either<AutostashPutBack, GitError> = either {
        // No time limit, as git-lfs's filter may run
        val apply = gitCli.execute(workTree, listOf("stash", "apply", id), timeout = Duration.INFINITE).bind()

        if (apply.exitCode == 0) {
            Either.Ok(AutostashPutBack.Applied)
        } else {
            printLog(TAG, "The autostash didn't apply, so it goes to the stash list: ${apply.stderr.trim()}")
            gitCli.run(workTree, listOf("stash", "store", "-m", AUTOSTASH_MESSAGE, "-q", id)).bind()

            Either.Ok(AutostashPutBack.Stored(notice(workTree, setAsideFor, after)))
        }
    }

    /** Shows the notice, with the files where putting the changes back left conflicts, and returns them. */
    private suspend fun notice(
        workTree: File,
        setAsideFor: AutostashOperation,
        after: AutostashPutBackAfter,
    ): List<String> {
        val conflicts = gitCli.run(workTree, listOf("ls-files", "--unmerged", "-z"))
            .okOrNull()
            ?.let(::unmergedPaths)
            .orEmpty()

        repositoryStateRepository.addAutostashNotice(AutostashNotice(setAsideFor, after, conflicts))

        return conflicts
    }
}

/**
 * Puts back the changes that `merge.autoStash` set aside after Leaf's commit or abort (fork-only), as `git commit` and
 * `git merge --abort` do. Like git, it removes `MERGE_AUTOSTASH` once they're applied or stored, and keeps it when
 * that fails, so that they're never lost.
 */
class PutBackMergeAutostashGitAction @Inject constructor(
    private val autostashes: Autostashes,
    private val jgit: JGit,
) : IPutBackMergeAutostashGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        after: AutostashPutBackAfter,
    ): Either<AutostashPutBack, GitError> = either {
        val workTree = jgit.provide(repositoryPath) { git -> git.repository.workTree }.bind()
        val autostash = autostashes.mergeAutostash(workTree).bind()

        if (autostash == null) {
            Either.Ok(AutostashPutBack.None)
        } else {
            val putBack = autostashes.putBack(workTree, autostash, AutostashOperation.MERGE, after).bind()
            autostashes.deleteMergeAutostash(workTree, autostash).bind()

            Either.Ok(putBack)
        }
    }
}

/**
 * Undoes a squash merge that waits to be committed with `git reset --merge` (fork-only), and puts back the changes
 * that `merge.autoStash` set aside, as `git merge --abort` does for a merge: it takes `MERGE_AUTOSTASH` out first,
 * as `git reset --merge` would store them in the stash list (git 2.54). When git refuses, the squash merge waits as
 * before, with them.
 */
class AbortSquashMergeGitAction @Inject constructor(
    private val autostashes: Autostashes,
    private val gitCli: GitCli,
    private val jgit: JGit,
) : IAbortSquashMergeGitAction {
    override suspend fun invoke(repositoryPath: String): Either<Unit, GitError> = either {
        val workTree = jgit.provide(repositoryPath) { git -> git.repository.workTree }.bind()
        val autostash = autostashes.mergeAutostash(workTree).bind()

        if (autostash != null) {
            autostashes.deleteMergeAutostash(workTree, autostash).bind()
        }

        val reset = gitCli.run(workTree, listOf("reset", "--merge"), timeout = Duration.INFINITE).mapOk { }

        if (autostash != null) {
            if (reset is Either.Ok) {
                autostashes.putBack(workTree, autostash, AutostashOperation.MERGE, AutostashPutBackAfter.ABORT)
                    .onErr { autostashes.restoreMergeAutostash(workTree, autostash) }
                    .bind()
            } else {
                autostashes.restoreMergeAutostash(workTree, autostash).bind()
            }
        }

        reset
    }
}
