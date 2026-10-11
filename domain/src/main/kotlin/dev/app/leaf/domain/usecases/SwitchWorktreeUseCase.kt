// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IOpenRepositoryGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.worktrees.gitDirOf
import dev.app.leaf.domain.worktrees.withCurrentWorktree
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** How long a switch waits at most for the new worktree's status and state, with input blocked. */
private val STATUS_WAIT = 10.seconds

/**
 * Switches the tab to another worktree of its repository (fork-only), the way a checkout switches it to another branch:
 * the tab keeps its view models, and only the git dir it points at changes. Then the file watcher watches that
 * worktree, and everything is refreshed.
 */
class SwitchWorktreeUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val openRepositoryGitAction: IOpenRepositoryGitAction,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val observeRepositoryToRefreshUseCase: ObserveRepositoryToRefreshUseCase,
    private val refreshDataUseCase: RefreshDataUseCase,
) {
    /** Switches to the worktree whose folder is [worktreePath]. Nothing changes when it's already the tab's. */
    operator fun invoke(worktreePath: String): Job = useCaseExecutor.executeLaunch(
        taskType = TaskType.SwitchWorktree,
        dataToRefresh = emptyArray(),
    ) { currentGitDir ->
        val gitDir = openRepositoryGitAction(worktreePath).bind()

        if (gitDirOf(gitDir) == gitDirOf(currentGitDir)) {
            return@executeLaunch Either.Ok(Unit)
        }

        val worktrees = repositoryDataRepository.worktrees.first().dataOrNull()

        observeRepositoryToRefreshUseCase.stop()
        repositoryDataRepository.switchWorktree(gitDir)

        // The worktree list, the picker and the branch badges tell the tab's worktree right away
        if (worktrees != null) {
            repositoryDataRepository.updateWorktrees(gitDir) { Either.Ok(worktrees.withCurrentWorktree(worktreePath)) }
        }

        observeRepositoryToRefreshUseCase()
        refreshDataUseCase(DataToRefresh.ALL)

        // The status pane keeps showing the old worktree's files and state (merging, rebasing) until the new ones load,
        // and an action on them would run in the new worktree. So input stays blocked until then, 10 seconds at most.
        withTimeoutOrNull(STATUS_WAIT) {
            repositoryDataRepository.status.first { it !is DataState.Loading }
            repositoryDataRepository.repositoryState.first { it !is DataState.Loading }
        }

        Either.Ok(Unit)
    }
}
