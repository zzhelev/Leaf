// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.discards.DiscardNotices
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.discards.DiscardRestore
import dev.app.leaf.domain.discards.DiscardRestorePlan
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetDiscardRecordsGitAction
import dev.app.leaf.domain.interfaces.IPlanDiscardRestoreGitAction
import dev.app.leaf.domain.interfaces.IRestoreDiscardGitAction
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/** The discards kept in the tab's worktree, newest first, for "Recently discarded" (fork-only, upstream issue 39). */
class GetDiscardRecordsUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getDiscardRecordsGitAction: IGetDiscardRecordsGitAction,
) {
    suspend operator fun invoke(): Either<List<DiscardRecord>, AppError> =
        useCaseExecutor.execute { repositoryPath -> getDiscardRecordsGitAction(repositoryPath) }
}

/** How Undo would bring back each file of [DiscardRecord], so that the user is asked when some changed since. */
class PlanDiscardRestoreUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val planDiscardRestoreGitAction: IPlanDiscardRestoreGitAction,
) {
    suspend operator fun invoke(record: DiscardRecord): Either<DiscardRestorePlan, AppError> =
        useCaseExecutor.execute { planDiscardRestoreGitAction(record.gitDir, record.id) }
}

/**
 * Undo of a discard: brings back what it threw away, in the worktree it was made in, and marks the tab's notice of it.
 * Files that changed since in ways that Leaf can't merge stay as they are.
 */
class RestoreDiscardUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val restoreDiscardGitAction: IRestoreDiscardGitAction,
    private val discardNotices: DiscardNotices,
) {
    operator fun invoke(record: DiscardRecord): Deferred<Either<DiscardRestore, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.RestoreDiscard,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG),
            // A file that changed since keeps its record: the status shows it as it is
            refreshEvenIfFailed = true,
        ) {
            restoreDiscardGitAction(record.gitDir, record.id).also { result ->
                if (result is Either.Ok) {
                    discardNotices.restored(result.value)
                }
            }
        }
}
