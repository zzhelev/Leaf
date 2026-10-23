// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.branches.BranchCopy
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.ICheckRemoteBranchesGitAction
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
    private val checkRemoteBranchesGitAction: ICheckRemoteBranchesGitAction,
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

    /**
     * Deletes the local [branches], then on their remotes the [copies] (by the local branch's full name) of the ones
     * that went, one push for each remote, each copy with its lease, and refreshes once. A copy whose local branch
     * stays, stays too. [onLocalDeleted] gets the local branches' result before git runs, for the dialog to keep should
     * the user stop git. A remote that can't be reached keeps its copies, and the result says why ([BranchesDeletion.copyErrors]).
     */
    suspend fun withCopies(
        branches: List<BranchToDelete>,
        copies: Map<String, BranchCopy>,
        onLocalDeleted: (BranchesDeletion) -> Unit,
    ): Either<BranchesDeletion, AppError> =
        useCaseExecutor.execute(dataToRefresh = arrayOf(DataToRefresh.ALL)) { repositoryPath ->
            val local = when (val result = deleteBranchesGitAction(repositoryPath, branches)) {
                is Either.Err -> return@execute Either.Err(result.error)
                is Either.Ok -> result.value
            }

            onLocalDeleted(local)

            val deleted = local.deleted.map { it.name }.toSet()
            val outcomes = mutableMapOf<String, BranchDeletionOutcome>()
            val errors = mutableMapOf<String, AppError>()
            val messages = mutableListOf<String>()

            for ((remote, group) in copies.filterKeys { it in deleted }.entries.groupBy { it.value.remote }) {
                val toDelete = group.map { it.value.info.branch }.distinctBy { it.name }

                when (val result = deleteRemoteBranchesGitAction(repositoryPath, remote, toDelete)) {
                    is Either.Err -> errors[remote] = result.error
                    is Either.Ok -> {
                        val byName = result.value.outcomes.associateBy { it.branch.name }

                        for ((localName, copy) in group) {
                            byName[copy.info.branch.name]?.let { outcomes[localName] = it }
                        }

                        result.value.remoteMessage?.let { messages += it }
                    }
                }
            }

            Either.Ok(
                local.copy(
                    copies = outcomes,
                    copyErrors = errors,
                    remoteMessage = messages.joinToString("\n").ifBlank { null },
                )
            )
        }

    /**
     * After the user stopped git while it deleted on [remote]: asks the server which of [branches] it still has, as it
     * may have deleted them without git, and refreshes, also when it can't tell.
     */
    suspend fun afterStop(remote: String, branches: List<BranchToDelete>): Either<List<BranchDeletionOutcome>, AppError> =
        useCaseExecutor.execute(dataToRefresh = arrayOf(DataToRefresh.ALL), refreshEvenIfFailed = true) { repositoryPath ->
            when (val result = checkRemoteBranchesGitAction(repositoryPath, remote, branches)) {
                is Either.Err -> Either.Err(result.error)
                is Either.Ok -> Either.Ok(result.value)
            }
        }
}
