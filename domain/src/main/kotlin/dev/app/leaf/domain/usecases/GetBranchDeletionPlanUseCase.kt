// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetBranchDeletionInfoGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeBaseBranchGitAction
import dev.app.leaf.domain.worktrees.mergeTarget
import javax.inject.Inject

/**
 * Reads what the "Delete branches" dialog shows (fork-only, upstream issue 116): for each branch whether a worktree
 * uses it, whether it's merged into the base, and the commits that only it has. A query, so it refreshes nothing.
 */
class GetBranchDeletionPlanUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getWorktreeBaseBranchGitAction: IGetWorktreeBaseBranchGitAction,
    private val getBranchDeletionInfoGitAction: IGetBranchDeletionInfoGitAction,
) {
    suspend operator fun invoke(request: BranchesDeletionRequest): Either<BranchDeletionPlan, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            val base = when (val result = getWorktreeBaseBranchGitAction(repositoryPath)) {
                is Either.Err -> return@execute Either.Err(result.error)
                is Either.Ok -> result.value.effective
            }

            val names = request.branches.filter { it.isLocal }.map { it.name }
            // "Delete merged branches" never offers the base itself, nor the local branch of a remote base, which
            // would be merged into it by definition
            val offered = if (request.mergedOnly) {
                val baseBranch = mergeTarget(base, names)

                names.filter { it != baseBranch }
            } else {
                names
            }

            when (val result = getBranchDeletionInfoGitAction(repositoryPath, base, offered)) {
                is Either.Err -> Either.Err(result.error)
                is Either.Ok -> Either.Ok(BranchDeletionPlan(base, result.value, request.mergedOnly))
            }
        }
}
