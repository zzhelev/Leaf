// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IRunRebasePlanGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.RebasePlan
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/** Rewrites a branch's commits as a commit editor's plan says, when it doesn't stop (fork-only, B3). */
class RunRebasePlanUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val runRebasePlanGitAction: IRunRebasePlanGitAction,
) {
    /** The result, for the strip that says what was done and can undo it. */
    operator fun invoke(plan: RebasePlan): Deferred<Either<BranchRewriteResult, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.RunRebasePlan,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            runRebasePlanGitAction(repositoryPath, plan)
        }
}
