// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IWriteConflictVersionGitAction
import javax.inject.Inject

/**
 * Opens one version of a conflicted file in the system's default app for its type, from a copy in Leaf's temporary
 * folder, as the diff pane opens an image. The resolver's choice cards offer it for binary files.
 */
class OpenConflictVersionUseCase @Inject constructor(
    private val writeConflictVersionGitAction: IWriteConflictVersionGitAction,
    private val openFileInExternalAppUseCase: OpenFileInExternalAppUseCase,
    private val tempFilesManager: TempFilesManager,
    private val useCaseExecutor: UseCaseExecutor,
) {
    /** [objectId] is the version's blob, and [path] the conflicted file's, whose name the copy takes. */
    suspend operator fun invoke(objectId: String, path: String): Either<Unit, AppError> {
        val written = useCaseExecutor.execute { repositoryPath ->
            writeConflictVersionGitAction(repositoryPath, objectId, path, tempFilesManager.tempDir().path)
        }

        return when (written) {
            is Either.Ok -> Either.Ok(openFileInExternalAppUseCase(written.value))
            is Either.Err -> written
        }
    }
}
