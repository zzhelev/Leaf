// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.ISaveWorktreeBaseBranchGitAction
import javax.inject.Inject

/**
 * Chooses the branch that the repository's worktrees are compared to, or goes back to the one Leaf picks by itself
 * when the branch is null, then compares the worktrees again.
 */
class SetWorktreeBaseBranchUseCase @Inject constructor(
    private val saveWorktreeBaseBranchGitAction: ISaveWorktreeBaseBranchGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(branch: String?): Either<Unit, AppError> =
        useCaseExecutor.execute(dataToRefresh = arrayOf(DataToRefresh.WORKTREES)) { repositoryPath ->
            saveWorktreeBaseBranchGitAction(repositoryPath, branch)
        }
}
