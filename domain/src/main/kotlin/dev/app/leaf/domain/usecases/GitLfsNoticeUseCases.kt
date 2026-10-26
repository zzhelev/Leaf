// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IDismissGitLfsNoticeGitAction
import dev.app.leaf.domain.interfaces.ISetUpGitLfsGitAction
import dev.app.leaf.domain.lfs.GitLfsNotice
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Job
import javax.inject.Inject

/**
 * Sets git-lfs up for the user's git and the repository (fork-only), from the strip that says git outside Leaf lacks
 * it. The notice is read again even after a failure: git-lfs writes the filter before the hooks that a hook in the way
 * stops.
 */
class SetUpGitLfsUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val setUpGitLfsGitAction: ISetUpGitLfsGitAction,
) {
    operator fun invoke(): Job = useCaseExecutor.executeLaunch(
        taskType = TaskType.SetUpGitLfs,
        dataToRefresh = arrayOf(DataToRefresh.REPO_STATE, DataToRefresh.STATUS),
        refreshEvenIfFailed = true,
    ) { repositoryPath ->
        setUpGitLfsGitAction(repositoryPath)
    }
}

/** Stops showing [GitLfsNotice] for the repository (fork-only), then reads the notice again, which hides the strip. */
class DismissGitLfsNoticeUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val dismissGitLfsNoticeGitAction: IDismissGitLfsNoticeGitAction,
) {
    suspend operator fun invoke(notice: GitLfsNotice): Either<Unit, AppError> =
        useCaseExecutor.execute(dataToRefresh = arrayOf(DataToRefresh.REPO_STATE)) { repositoryPath ->
            dismissGitLfsNoticeGitAction(repositoryPath, notice)
        }
}
