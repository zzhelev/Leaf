// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.DataRefreshRunner
import dev.app.leaf.domain.Pagination
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.ISaveLogBranchFilterGitAction
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

/**
 * Saves which branches the log shows (fork-only, upstream issue 207), for every worktree of the repository, then walks
 * the log again from them. Only the log: the branches, worktrees and the rest are as they were. It takes its turn with
 * the tab's refreshes, which share the log's generator. A query: no task, no processing screen.
 */
class SetLogBranchFilterUseCase @Inject constructor(
    private val saveLogBranchFilterGitAction: ISaveLogBranchFilterGitAction,
    private val getLogUseCase: GetLogUseCase,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val dataRefreshRunner: DataRefreshRunner,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(filter: LogBranchFilter): Either<Unit, AppError> =
        useCaseExecutor.execute { repositoryPath ->
            saveLogBranchFilterGitAction(repositoryPath, filter).bind()

            dataRefreshRunner.runAlone {
                repositoryDataRepository.updateLog(repositoryPath) {
                    getLogUseCase(repositoryPath, pagination = Pagination.None)
                }
            }

            Either.Ok(Unit)
        }
}
