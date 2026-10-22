// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.redactUrlPassword
import dev.app.leaf.data.git.cli.remote.RemoteOperationsBackend
import dev.app.leaf.data.git.countCommitsOnlyOn
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.DeletionRemote
import dev.app.leaf.domain.branches.TipCommit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.interfaces.IGetRemoteBranchDeletionInfoGitAction
import dev.app.leaf.domain.interfaces.RemoteBranchDeletionRead
import dev.app.leaf.domain.models.MergedIntoBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import javax.inject.Inject

private const val TAG = "GetRemoteBranchDeletionInfoGitAction"

/**
 * Reads what deleting a remote's branches would do (fork-only, follows upstream issue 116), from its remote-tracking
 * branches as last fetched, as the local [GetBranchDeletionInfoGitAction] reads local branches. The remote is asked
 * nothing: the delete's lease refuses a branch whose commit on the server isn't the one read here.
 */
class GetRemoteBranchDeletionInfoGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
    private val backend: RemoteOperationsBackend,
) : IGetRemoteBranchDeletionInfoGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        remote: String,
        base: String?,
        branches: List<String>,
        onlyMerged: Boolean,
    ): Either<RemoteBranchDeletionRead, GitError> {
        // Only git deletes them, so the dialog says why it can't before the user picks any
        backend.whyNotGitCli()?.let { return Either.Err(RemoteBranchesNeedGitError(it)) }

        return jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                read(git.repository, File(repositoryPath), remote, base, branches, onlyMerged)
            }
        }
    }

    private suspend fun read(
        repository: Repository,
        folder: File,
        remote: String,
        base: String?,
        branches: List<String>,
        onlyMerged: Boolean,
    ): RemoteBranchDeletionRead {
        val prefix = "${Constants.R_REMOTES}$remote/"
        val headName = "$prefix${Constants.HEAD}"
        val defaultBranch = repository.exactRef(headName)?.takeIf { it.isSymbolic }?.target?.name
        // Without a HEAD on the remote, merged means merged into the worktrees' base, and its branch of the same
        // name on the remote stands for the default branch
        val mergeBase = defaultBranch ?: base?.takeIf { repository.exactRef(it) != null }
        val baseName = base?.let {
            if (it.startsWith(Constants.R_REMOTES)) {
                it.removePrefix(Constants.R_REMOTES).substringAfter('/')
            } else {
                it.removePrefix(Constants.R_HEADS)
            }
        }
        val baseCopy = baseName?.takeIf { defaultBranch == null }?.let { prefix + it }

        val existing = branches.distinct().mapNotNull { name ->
            repository.exactRef(name)?.takeIf { name.startsWith(prefix) }?.let { ref ->
                ref.objectId?.let { name to it }
            }
        }

        val refusals = existing.associate { (name, _) ->
            name to when (name) {
                headName -> BranchKept.RemoteHead
                defaultBranch -> BranchKept.RemoteDefaultBranch(isNamedByRemote = true)
                baseCopy -> BranchKept.RemoteDefaultBranch(isNamedByRemote = false)
                else -> null
            }
        }

        val deletable = existing.map { it.first }.filter { refusals.getValue(it) == null }.toSet()
        val merged = gitCli.mergedIntoBase(repository, folder, mergeBase, existing.filter { it.first in deletable })
        val tips = tipCommits(repository, existing)

        val infos = existing.map { (name, tip) ->
            val commitsOnlyHere = when {
                refusals.getValue(name) != null -> 0
                // The base stays, so a branch in it has no commit of its own
                merged[name] == MergedIntoBase.COMMITS && mergeBase !in deletable -> 0
                // Not shown, so not worth a walk of every ref
                onlyMerged && merged[name] == null -> 0
                else -> repository.countCommitsOnlyOn(name, alsoIgnoring = deletable)
            }

            BranchDeletionInfo(
                branch = BranchToDelete(name, tip.name),
                refusal = refusals.getValue(name),
                merged = merged[name],
                commitsOnlyHere = commitsOnlyHere,
                lastCommit = tips[name],
            )
        }

        val url = repository.config.getString("remote", remote, "url")

        return RemoteBranchDeletionRead(
            remote = DeletionRemote(remote, url?.let(::redactUrlPassword), lastFetchedMillis(repository, url)),
            base = mergeBase,
            infos = infos,
        )
    }

    private fun tipCommits(repository: Repository, branches: List<Pair<String, ObjectId>>): Map<String, TipCommit> =
        RevWalk(repository).use { walk ->
            branches.mapNotNull { (name, tip) ->
                try {
                    val commit = walk.parseCommit(tip)

                    name to TipCommit(commit.authorIdent.name, commit.commitTime * 1000L)
                } catch (e: Exception) {
                    printError(TAG, "Could not read the tip of $name: ${e.message}")
                    null
                }
            }.toMap()
        }
}

/**
 * When the remote at [url] was last fetched from, as far as `FETCH_HEAD` tells: its time, when it names that URL as git
 * writes it there ([fetchHeadUrl]). Leaf fetches one remote at a time, so a later fetch of another remote hides the
 * time. Null then, or when nothing was fetched in this worktree or the main one.
 */
internal fun lastFetchedMillis(repository: Repository, url: String?): Long? {
    if (url == null) return null

    val written = " of ${fetchHeadUrl(url)}"

    return listOfNotNull(repository.directory, repository.commonDirectory)
        .map { File(it, "FETCH_HEAD") }
        .distinct()
        .filter { file ->
            try {
                file.isFile && file.readLines().any { it.endsWith(written) }
            } catch (e: Exception) {
                false
            }
        }
        .maxOfOrNull { it.lastModified() }
}

/**
 * [url] as git writes it in `FETCH_HEAD` (`builtin/fetch.c`, git 2.54): without its user and password
 * (`transport_anonymize_url`), its trailing slashes and a trailing `.git`.
 */
internal fun fetchHeadUrl(url: String): String {
    val anonymized = anonymizedUrl(url).trimEnd('/')

    return if (anonymized.length > 5 && anonymized.endsWith(".git")) anonymized.dropLast(4) else anonymized
}

/** git's `transport_anonymize_url`: the URL without what comes before its host's `@`, for URLs that have a host. */
private fun anonymizedUrl(url: String): String {
    val at = url.indexOf('@')
    val scheme = url.indexOf("://")

    if (at < 0) return url

    if (scheme < 0) {
        val colon = url.indexOf(':')
        val slash = url.indexOf('/')
        // A local path, or not "user@host:path"
        val isLocal = colon < 0 || (slash in 0 until colon)

        return if (isLocal || !url.substring(at + 1).contains(':')) url else url.substring(at + 1)
    }

    // An @ past the first slash after the scheme is in the path
    val slash = url.indexOf('/', scheme + 3)

    return if (slash in 0 until at) url else url.substring(0, scheme + 3) + url.substring(at + 1)
}
