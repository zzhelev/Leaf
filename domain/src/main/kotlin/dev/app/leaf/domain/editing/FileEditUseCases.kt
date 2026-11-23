// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editing

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IReadFileForEditGitAction
import dev.app.leaf.domain.interfaces.IWriteFileEditGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/**
 * Reads a working file and its index version for the whole file's editor (fork-only, `docs/fork/inline-editing.md`,
 * B2), as a task without the processing screen: a file that can't be edited (too long, gone) opens `ErrorDialog`.
 */
class ReadFileForEditUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val readFileForEdit: IReadFileForEditGitAction,
) {
    operator fun invoke(path: String): Deferred<Either<FileForEdit, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.OpenFileEdit,
            dataToRefresh = emptyArray(),
            isForegroundTask = false,
        ) { repositoryPath ->
            readFileForEdit(repositoryPath, path)
        }
}

/**
 * Save of the whole file's editor (fork-only, B2): writes the text typed into the working file, as a task without the
 * processing screen or a toast; the discard strip says what changed, with Undo. A failure opens `ErrorDialog`, and the
 * editor keeps what was typed.
 */
class SaveFileEditUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val writeFileEdit: IWriteFileEditGitAction,
) {
    operator fun invoke(edit: FileEdit, typed: String): Deferred<Either<ByteArray, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.SaveFileEdit,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            refreshEvenIfFailed = true,
            isForegroundTask = false,
        ) { repositoryPath ->
            writeFileEdit(repositoryPath, edit, typed)
        }
}
