// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetFileRestorePlanGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.restore.FileRestorePlan
import javax.inject.Inject

/** What "Restore this version of the file" would replace, for its confirmation. */
class GetFileRestorePlanUseCase @Inject constructor(
    private val getFileRestorePlanGitAction: IGetFileRestorePlanGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(commit: Commit, filePath: String): Either<FileRestorePlan, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            getFileRestorePlanGitAction(repositoryPath, commit.hash, filePath)
        }
}
