// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IRebaseBranchGitAction
import dev.app.leaf.domain.interfaces.IsMultiStep
import dev.app.leaf.domain.models.Branch
import javax.inject.Inject

/**
 * Rebases what is checked out onto [Branch] with `git rebase` (fork-only), and returns whether it stopped at conflicts.
 * git then goes on with it: Continue, Skip and Abort run `git rebase` too (`GitCliRebase`).
 */
class GitCliRebaseBranchGitAction @Inject constructor(
    private val operations: GitCliOperations,
) : IRebaseBranchGitAction {
    override suspend fun invoke(repositoryPath: String, branch: Branch): Either<IsMultiStep, GitError> = either {
        val branchArgument = operations.branchArgument(repositoryPath, branch.name).bind()

        operations.run(repositoryPath, listOf("rebase", branchArgument))
    }
}
