// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IWriteConflictResultGitAction
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.TextMerge
import javax.inject.Inject

/**
 * The resolver's Save: writes the result into the working file, with markers for the conflicts still open, and
 * returns the id of the bytes written, for the next Save's check. The file watcher refreshes the status. Returns the
 * result, so that the resolver shows a failure itself.
 */
class SaveConflictResolutionUseCase @Inject constructor(
    private val writeConflictResultGitAction: IWriteConflictResultGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    /** [overwriteChanges]: the user chose to keep the resolver's result over changes made to the file since. */
    suspend operator fun invoke(
        file: ConflictedFile,
        merge: TextMerge,
        overwriteChanges: Boolean = false,
    ): Either<String, AppError> = useCaseExecutor.execute { repositoryPath ->
        writeConflictResultGitAction(repositoryPath, file, merge, overwriteChanges)
    }
}
