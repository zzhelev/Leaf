// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDiscardAllGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.sorting.DiscardAllPlan
import kotlinx.coroutines.Job
import javax.inject.Inject

/** "Discard all" in the Unstaged pane: restores the tracked files, and with [deleteNewFiles] deletes the new ones. */
class DiscardAllUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val discardAllGitAction: IDiscardAllGitAction,
) {
    operator fun invoke(plan: DiscardAllPlan, deleteNewFiles: Boolean): Job {
        return useCaseExecutor.executeLaunch(
            taskType = TaskType.DiscardAllFiles,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG),
            // A file that became conflicted since the dialog opened refuses the discard: the status shows it then
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            discardAllGitAction(
                repositoryPath = repositoryPath,
                restore = plan.restored,
                newFiles = if (deleteNewFiles) plan.newFiles else emptyList(),
            )
        }
    }
}
