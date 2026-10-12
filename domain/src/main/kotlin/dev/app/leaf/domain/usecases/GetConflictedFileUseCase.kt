// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetConflictedFileGitAction
import dev.app.leaf.domain.models.ConflictedFile
import javax.inject.Inject

/** Loads a conflicted file for the built-in resolver. */
class GetConflictedFileUseCase @Inject constructor(
    private val getConflictedFileGitAction: IGetConflictedFileGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(path: String): Either<ConflictedFile, AppError> =
        useCaseExecutor.execute { repositoryPath -> getConflictedFileGitAction(repositoryPath, path) }
}
