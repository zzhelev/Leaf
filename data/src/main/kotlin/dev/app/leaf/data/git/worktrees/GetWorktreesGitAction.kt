// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetWorktreesGitAction
import dev.app.leaf.domain.models.Worktree
import java.io.File
import javax.inject.Inject

class GetWorktreesGitAction @Inject constructor(
    private val gitCli: GitCli,
    private val jgit: JGit,
) : IGetWorktreesGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<List<Worktree>, GitError> = either {
        // git finds the repository from its git dir, including a linked worktree's (<common dir>/worktrees/<name>)
        val output = gitCli.run(File(repositoryPath), listOf("worktree", "list", "--porcelain", "-z")).bind()

        val (currentWorkTree, heads) = jgit.provide(repositoryPath) { git ->
            git.repository.workTree.canonicalFile to git.repository.worktreeHeads()
        }.bind()

        // git lists rebasing and bisecting worktrees as detached, so the branch they use comes from their git dirs
        val headsByFolder = heads.associateBy { File(it.path).canonicalFile }

        Either.Ok(
            parseWorktreeList(output).map { worktree ->
                val folder = File(worktree.path).canonicalFile
                val head = headsByFolder[folder]

                worktree.copy(
                    isCurrent = folder == currentWorkTree,
                    rebasingBranch = head?.rebasingBranch,
                    bisectingBranch = head?.bisectingBranch,
                )
            }
        )
    }
}
