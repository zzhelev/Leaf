// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editing

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IWriteHunkEditGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/**
 * Done of a hunk edited in place (fork-only, `docs/fork/inline-editing.md`): writes the lines typed into the working
 * file, as a task without the processing screen or a toast; the discard strip says what changed, with Undo. A failure
 * opens `ErrorDialog`, and the result tells the field to stay open.
 */
class EditHunkUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val writeHunkEdit: IWriteHunkEditGitAction,
) {
    operator fun invoke(edit: HunkEdit, typed: String): Deferred<Either<EditedFile, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.EditHunk,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // The diff shows the file as it is now, also when it changed under the edit
            refreshEvenIfFailed = true,
            isForegroundTask = false,
        ) { repositoryPath ->
            writeHunkEdit(repositoryPath, edit, typed)
        }
}
