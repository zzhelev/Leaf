// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IRewriteBranchGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/** Squashes, fixes up or rewords a branch's commits (fork-only, `docs/fork/squash-and-rebase.md`). */
class RewriteBranchUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val rewriteBranchGitAction: IRewriteBranchGitAction,
) {
    /** The result, for the strip that says what was done and can undo it. */
    operator fun invoke(rewrite: BranchRewrite): Deferred<Either<BranchRewriteResult, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.RewriteBranch,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            rewriteBranchGitAction(repositoryPath, rewrite)
        }
}
