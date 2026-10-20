// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetOtherWorktreeHeadsGitAction
import dev.app.leaf.domain.models.Worktree
import java.io.File
import javax.inject.Inject

/**
 * Reads the `HEAD` files of the other worktrees: the log's refresh runs it each time, and a refresh starts no process
 * (a probe, 2026-10-20: the log took 42 ms, one process 18 ms, the files 0.7 ms). A reftable repository has only stubs
 * in them, so there `git worktree list` answers instead.
 */
class GetOtherWorktreeHeadsGitAction @Inject constructor(
    private val gitCli: GitCli,
    private val jgit: JGit,
) : IGetOtherWorktreeHeadsGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<List<String>, GitError> = either {
        val (heads, currentFolder) = jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            repository.otherWorktreeHeads() to if (repository.isBare) null else repository.workTree.canonicalFile
        }.bind()

        if (!heads.needsGit) {
            Either.Ok(heads.commits)
        } else {
            val output = gitCli.run(File(repositoryPath), listOf("worktree", "list", "--porcelain", "-z")).bind()

            Either.Ok(
                parseWorktreeList(output)
                    .filter { it.isOtherDetachedWorktree(currentFolder) }
                    .mapNotNull { it.headSha }
                    .distinct()
            )
        }
    }

    /** git's `prunable` covers a missing folder, but a locked worktree isn't prunable, so the folder is looked at too. */
    private fun Worktree.isOtherDetachedWorktree(currentFolder: File?): Boolean =
        isDetached && !isBare && prunable == null && headSha != null &&
            File(path).isDirectory && File(path).canonicalFile != currentFolder
}
