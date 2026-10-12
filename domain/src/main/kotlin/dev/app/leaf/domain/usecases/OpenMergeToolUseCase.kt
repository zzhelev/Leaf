// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IOpenMergeToolGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

/**
 * Resolves a conflicted file in the user's merge tool. The tab waits, behind the processing screen, until the tool
 * closes; its Cancel stops waiting.
 */
class OpenMergeToolUseCase @Inject constructor(
    private val openMergeToolGitAction: IOpenMergeToolGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(filePath: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.OpenMergeTool,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // The file may have changed even when the tool failed
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            openMergeToolGitAction(repositoryPath, filePath)
        }
    }
}
