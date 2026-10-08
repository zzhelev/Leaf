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
        val currentWorkTree = jgit.provide(repositoryPath) { git -> git.repository.workTree.canonicalFile }.bind()

        Either.Ok(
            parseWorktreeList(output).map { worktree ->
                worktree.copy(isCurrent = File(worktree.path).canonicalFile == currentWorkTree)
            }
        )
    }
}
