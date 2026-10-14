// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetRenamedFilesGitAction
import dev.app.leaf.domain.models.RenamedFile
import javax.inject.Inject

/** The files that each side renamed differently, which the resolver lists as one entry each. */
class GetRenamedFilesUseCase @Inject constructor(
    private val getRenamedFilesGitAction: IGetRenamedFilesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): Either<List<RenamedFile>, AppError> =
        useCaseExecutor.execute { repositoryPath -> getRenamedFilesGitAction(repositoryPath) }
}
