// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.common.printLog
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.worktrees.repositoryOfRemovedWorktree
import dev.app.leaf.domain.worktrees.workTreeOf
import java.io.File
import javax.inject.Inject

private const val TAG = "FollowRemovedWorktree"

/**
 * Switches the tab to the main worktree when its own worktree is removed (fork-only): by another tab, a terminal or an
 * agent. The tab would otherwise keep a git dir that no longer exists, and every refresh would fail.
 */
class FollowRemovedWorktreeUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val switchWorktreeUseCase: SwitchWorktreeUseCase,
) {
    /**
     * Checks each time the worktree list is read, which a removal triggers through the file watcher: the list fails once
     * the tab's git dir is gone, and lists the worktree as prunable once its folder is. Runs until it's cancelled.
     */
    suspend operator fun invoke() {
        var followed: String? = null

        repositoryDataRepository.worktrees.collect { state ->
            if (state is DataState.Loading) return@collect

            val gitDir = repositoryDataRepository.repositoryPath ?: return@collect
            val repository = repositoryOfRemovedWorktree(gitDir) ?: return@collect

            // The list is read again while the switch runs
            if (gitDir == followed) return@collect
            followed = gitDir

            // A bare repository has no main working tree; its git dir opens as it is
            val mainWorktree = workTreeOf(repository).takeIf { File(it, ".git").exists() } ?: repository

            printLog(TAG, "The tab's worktree $gitDir was removed, switching to $mainWorktree")
            switchWorktreeUseCase.followRemovedWorktree(mainWorktree).join()
        }
    }
}
