// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IResolveRenamedFileGitAction
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.RenamedFile
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

/** Resolves a file that each side renamed differently by the name, or names, picked in the resolver. */
class ResolveRenamedFileUseCase @Inject constructor(
    private val resolveRenamedFileGitAction: IResolveRenamedFileGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(file: RenamedFile, keep: Set<ConflictSide>) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ResolveConflict,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // git may have changed some of the paths before it failed
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            resolveRenamedFileGitAction(repositoryPath, file, keep)
        }
    }
}
