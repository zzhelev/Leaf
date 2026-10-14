// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IResolveConflictGitAction
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

/**
 * Resolves conflicted files by taking one side's version of each ("Use ours", "Use theirs"). [keepWorkingFile]: the
 * working file is staged as it is where the side has a file (the resolver's Keep, for a file that the other deleted).
 */
class ResolveConflictUseCase @Inject constructor(
    private val resolveConflictGitAction: IResolveConflictGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(filePaths: List<String>, side: ConflictSide, keepWorkingFile: Boolean = false) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ResolveConflict,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // git may have resolved some of the files before it failed
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            resolveConflictGitAction(repositoryPath, filePaths, side, keepWorkingFile)
        }
    }
}
