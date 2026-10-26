// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IAbortRebaseGitAction
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/**
 * "Back to the plan" (fork-only, B5): aborts the rebase of a commit editor's plan, which puts the branch back where the
 * plan found it, so that the editor opens with the plan again.
 */
class BackToRebasePlanUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val abortRebaseGitAction: IAbortRebaseGitAction,
) {
    operator fun invoke(): Deferred<Either<Unit, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.BackToRebasePlan,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            abortRebaseGitAction(repositoryPath)
        }
}
