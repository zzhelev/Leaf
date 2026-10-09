// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.LfsDownloadError
import dev.app.leaf.domain.models.TaskProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GitLfsFetch"

private val LFS_VERSION_TIMEOUT = 10.seconds

/**
 * Downloads the LFS objects of a commit with git-lfs, `git lfs fetch <remote> <commit>`, before JGit checks the commit
 * out, so that JGit's checkout finds them in the repository and Leaf's built-in LFS client downloads nothing. git-lfs
 * batches the downloads, follows the user's LFS config (`lfs.url`, `remote.<name>.lfsurl`, `lfs.fetchexclude`), asks
 * for credentials through git's helpers and Leaf's dialogs, and connects to SSH remotes with the system's ssh.
 *
 * Nothing runs when git-lfs isn't installed, which leaves the downloads to the built-in client, or when the commit's
 * `.gitattributes` doesn't use LFS: git-lfs would read the whole tree to find out.
 */
class GitLfsFetch @Inject constructor(
    private val gitCli: GitCli,
    private val remoteCommand: GitCliRemoteCommand,
) {
    /**
     * @param onProgress receives git-lfs's progress ("Downloading LFS objects"), by default on the processing screen.
     * @return whether git-lfs downloaded the objects; false when it isn't installed or the commit doesn't use LFS.
     */
    suspend operator fun invoke(
        repository: Repository,
        remote: String,
        commit: ObjectId,
        onProgress: ((TaskProgress?) -> Unit)? = null,
    ): Either<Boolean, GitError> {
        if (!usesLfs(repository, commit)) {
            return Either.Ok(false)
        }

        val directory = repository.commandDirectory()

        if (gitCli.run(directory, listOf("lfs", "version"), LFS_VERSION_TIMEOUT) !is Either.Ok) {
            printLog(TAG, "git-lfs isn't installed, so Leaf's built-in client downloads the LFS objects")
            return Either.Ok(false)
        }

        val args = listOf("lfs", "fetch", remote, commit.name)
        val fetch = if (onProgress == null) {
            remoteCommand.run(directory, args)
        } else {
            remoteCommand.run(directory, args, onProgress = onProgress)
        }

        val error = when (fetch) {
            is Either.Err -> fetch.error
            is Either.Ok -> if (fetch.value.exitCode == 0) {
                null
            } else {
                remoteOperationError(fetch.value.exitCode, fetch.value.stderr)
            }
        }

        return if (error == null) Either.Ok(true) else Either.Err(LfsDownloadError(error))
    }

    /**
     * Installs git-lfs's hooks in a repository that Leaf cloned (`git lfs update`), as `git clone` gets them when
     * git-lfs checks the files out: without its `pre-push` hook, git wouldn't upload LFS objects. A failure is only
     * logged, as pushes then go through JGit, which uploads them itself.
     */
    suspend fun installHooks(repository: Repository) {
        val result = gitCli.run(repository.commandDirectory(), listOf("lfs", "update"))

        if (result is Either.Err) {
            printLog(TAG, "Couldn't install git-lfs's hooks: ${result.error}")
        }
    }

    /** Whether [commit]'s root `.gitattributes` sends files through the LFS filter, as `git lfs track` writes it. */
    private suspend fun usesLfs(repository: Repository, commit: ObjectId): Boolean = withContext(Dispatchers.IO) {
        RevWalk(repository).use { walk ->
            val tree = walk.parseCommit(commit).tree
            val attributes = TreeWalk.forPath(repository, Constants.DOT_GIT_ATTRIBUTES, tree) ?: return@use false

            attributes.use { repository.open(it.getObjectId(0)).bytes.decodeToString().contains("filter=lfs") }
        }
    }
}
