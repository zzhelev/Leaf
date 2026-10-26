// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IStartRebasePlanGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.rewrite.PlanRebaseOutcome
import dev.app.leaf.domain.rewrite.RebasePlan
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/**
 * Replays a commit editor's plan that stops with `git rebase -i` (fork-only, B4). When it stops in another worktree, the
 * tab goes there, where its conflicts or its edit wait, as for a worktree's rebase onto the base.
 */
class StartRebasePlanUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val startRebasePlanGitAction: IStartRebasePlanGitAction,
    private val switchWorktreeUseCase: SwitchWorktreeUseCase,
) {
    /** How it went, with [autostash] for the box that sets the tab's uncommitted changes aside. */
    operator fun invoke(plan: RebasePlan, autostash: Boolean): Deferred<Either<PlanRebaseOutcome, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.StartRebasePlan,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            stoppedAtConflicts = { it is PlanRebaseOutcome.Stopped && it.atConflicts },
        ) { repositoryPath ->
            val outcome = startRebasePlanGitAction(repositoryPath, plan, autostash).bind()

            if (outcome is PlanRebaseOutcome.Stopped) {
                switchWorktreeUseCase.switch(repositoryPath, outcome.worktreePath).bind()
            }

            Either.Ok(outcome)
        }
}
