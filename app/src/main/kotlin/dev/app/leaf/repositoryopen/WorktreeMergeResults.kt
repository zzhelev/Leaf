// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull

private val WORKTREE_MERGES = setOf(TaskType.SquashMergeWorktree, TaskType.MergeWorktree)

/**
 * Each task among [completedTasks] from now on whose result waits in the uncommitted changes, which the tab then shows
 * (fork-only): a merge of a worktree's branch into the base, whether it stopped at conflicts or not, and a rebase of
 * one onto the base that stopped at conflicts, after which the tab switched to its worktree. A rebase that ended has
 * nothing to commit, and a task that failed changed nothing to show.
 */
fun worktreeResultsToShow(completedTasks: Flow<List<CompletedTask>>): Flow<CompletedTask> = completedTasks
    .drop(1)
    .mapNotNull { it.lastOrNull() }
    .distinctUntilChanged()
    .filter { task ->
        task is CompletedTask.Success && (
            task.taskType in WORKTREE_MERGES ||
                task.taskType == TaskType.RebaseWorktree && task.stoppedAtConflicts
            )
    }
