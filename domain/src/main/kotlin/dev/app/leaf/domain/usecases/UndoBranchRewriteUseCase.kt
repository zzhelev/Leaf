// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IUndoBranchRewriteGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import kotlinx.coroutines.Deferred
import javax.inject.Inject

/** The strip's Undo: puts a rewritten branch back where it was (fork-only). */
class UndoBranchRewriteUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val undoBranchRewriteGitAction: IUndoBranchRewriteGitAction,
) {
    operator fun invoke(rewrite: BranchRewriteResult): Deferred<Either<Unit, AppError>> =
        useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.UndoBranchRewrite,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            undoBranchRewriteGitAction(repositoryPath, rewrite)
        }
}
