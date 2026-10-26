// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetPlanRecordGitAction
import dev.app.leaf.domain.rewrite.PlanRecord
import javax.inject.Inject

/** The commit editor's plan that waits at a stop in the tab's worktree, or null (fork-only, B5). */
class GetPlanRecordUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getPlanRecordGitAction: IGetPlanRecordGitAction,
) {
    suspend operator fun invoke(): Either<PlanRecord?, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            getPlanRecordGitAction(repositoryPath)
        }
}
