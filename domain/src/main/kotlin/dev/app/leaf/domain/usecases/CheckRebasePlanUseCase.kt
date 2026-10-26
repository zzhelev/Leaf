// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.ICheckRebasePlanGitAction
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.RebasePlan
import javax.inject.Inject

/** Where a commit editor's plan stops, if it does, while it's edited (fork-only, `docs/fork/commit-editor.md`). */
class CheckRebasePlanUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val checkRebasePlanGitAction: ICheckRebasePlanGitAction,
) {
    suspend operator fun invoke(plan: RebasePlan): Either<PlanCheck, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            checkRebasePlanGitAction(repositoryPath, plan)
        }
}
