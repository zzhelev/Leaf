// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.WorktreesRefreshRunner
import dev.app.leaf.domain.models.WorktreesRefreshIntervals
import dev.app.leaf.domain.refresh.WorktreesVisibility
import dev.app.leaf.domain.refresh.worktreePollTicks
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Refreshes the worktree list on the interval set in the settings while [WorktreesVisibility] says it's shown, so
 * that the changes agents make in the other worktrees' working trees show up. See [worktreePollTicks].
 */
class PollWorktreesUseCase @Inject constructor(
    private val appSettingsService: AppSettingsService,
    private val refreshDataUseCase: RefreshDataUseCase,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val worktreesRefreshRunner: WorktreesRefreshRunner,
) {
    /** Runs until cancelled. */
    suspend operator fun invoke(visibility: Flow<WorktreesVisibility>) {
        worktreePollTicks(
            visibility = visibility,
            interval = appSettingsService.worktreesRefreshInterval.map(WorktreesRefreshIntervals::pollInterval),
            lastRefreshAt = { worktreesRefreshRunner.lastRunEndedAt },
        ).collect {
            // An operation refreshes what it changed when it ends
            if (repositoryStateRepository.currentTask.value == null) {
                refreshDataUseCase(DataToRefresh.WORKTREES).join()
            }
        }
    }
}
