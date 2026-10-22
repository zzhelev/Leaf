// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.cherrypick.CherryPickPlan
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.ICherryPickCommitsGitAction
import dev.app.leaf.domain.interfaces.IPersistSquashMessageGitAction
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Job
import javax.inject.Inject

/**
 * Cherry-picks a branch's commits onto the current branch (fork-only), as the dialog's [CherryPickPlan] shows: each as
 * a new commit, or their changes staged as one, with their messages in `SQUASH_MSG`. git stops at a commit's conflicts;
 * the task then ends as one that stopped at them, and the status pane's Continue goes on
 * ([CherryPickSequenceUseCase]). The message is written then too, so that the commit field has it at the end.
 */
class CherryPickCommitsUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val cherryPickCommitsGitAction: ICherryPickCommitsGitAction,
    private val persistSquashMessageGitAction: IPersistSquashMessageGitAction,
) {
    operator fun invoke(plan: CherryPickPlan): Job = useCaseExecutor.executeLaunch(
        taskType = if (plan.noCommit) TaskType.CherryPickCommitsAsOne else TaskType.CherryPickCommits,
        dataToRefresh = arrayOf(DataToRefresh.ALL),
        refreshEvenIfFailed = true,
        stoppedAtConflicts = { stoppedAtConflicts -> stoppedAtConflicts },
    ) { repositoryPath ->
        val stoppedAtConflicts = cherryPickCommitsGitAction(repositoryPath, plan.commits, plan.noCommit).bind()

        if (plan.squashMessage != null) {
            persistSquashMessageGitAction(repositoryPath, plan.squashMessage).bind()
        }

        Either.Ok(stoppedAtConflicts)
    }
}
