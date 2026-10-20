// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IRestoreFileVersionGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/** "Restore this version of the file" in file history: writes the file as [commit] had it into the working tree. */
class RestoreFileVersionUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val restoreFileVersionGitAction: IRestoreFileVersionGitAction,
) {
    /** The result, for file history to tell the user, besides the task's own toast or error dialog. */
    operator fun invoke(commit: Commit, filePath: String): Deferred<Either<Unit, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.RestoreFileVersion,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // git may have changed the file before it failed, and Leaf puts it back
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            restoreFileVersionGitAction(repositoryPath, commit.hash, filePath)
        }
}
