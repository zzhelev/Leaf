// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IDeleteBranchesGitAction
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchesGitAction
import javax.inject.Inject

/**
 * Deletes the branches that the user checked in the "Delete branches" dialog (fork-only, upstream issue 116), local
 * ones, or with [remote] that remote's on the server, and refreshes once at the end. It returns what happened to each
 * branch to the dialog, which stays open to show it: no processing screen and no toast, as the single delete's dialog
 * has none. The dialog shows that it's deleting a remote's, and can cancel it.
 */
class DeleteBranchesUseCase @Inject constructor(
    private val deleteBranchesGitAction: IDeleteBranchesGitAction,
    private val deleteRemoteBranchesGitAction: IDeleteRemoteBranchesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(
        branches: List<BranchToDelete>,
        remote: String? = null,
    ): Either<BranchesDeletion, AppError> =
        useCaseExecutor.execute(dataToRefresh = arrayOf(DataToRefresh.ALL)) { repositoryPath ->
            val result = if (remote != null) {
                deleteRemoteBranchesGitAction(repositoryPath, remote, branches)
            } else {
                deleteBranchesGitAction(repositoryPath, branches)
            }

            when (result) {
                is Either.Err -> Either.Err(result.error)
                is Either.Ok -> Either.Ok(result.value)
            }
        }
}
