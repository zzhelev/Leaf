// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IStageEntryGitAction
import dev.app.leaf.domain.interfaces.IWriteConflictResultGitAction
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TextMerge
import javax.inject.Inject

/**
 * The resolver's Mark resolved: saves the result, then stages the file, as staging it from Unstaged does. Refused while
 * a conflict is open, so that no markers get staged.
 */
class MarkConflictResolvedUseCase @Inject constructor(
    private val writeConflictResultGitAction: IWriteConflictResultGitAction,
    private val stageEntryGitAction: IStageEntryGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(
        file: ConflictedFile,
        merge: TextMerge,
        overwriteChanges: Boolean = false,
    ): Either<Unit, AppError> = useCaseExecutor.execute(
        dataToRefresh = arrayOf(DataToRefresh.STATUS),
    ) { repositoryPath ->
        if (merge.openConflicts > 0) {
            raiseError(ConflictError.ConflictsLeft(file.path, merge.openConflicts))
        }

        writeConflictResultGitAction(repositoryPath, file, merge, overwriteChanges).bind()
        stageEntryGitAction(repositoryPath, StatusEntry(file.path, StatusType.CONFLICTING))
    }
}
