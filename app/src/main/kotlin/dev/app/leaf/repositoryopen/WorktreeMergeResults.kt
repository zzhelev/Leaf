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
 * Each merge of a worktree's branch into the base that ends among [completedTasks] from now on (fork-only), whether it
 * stopped at conflicts or not. Its result waits in the uncommitted changes, which the tab then shows. A merge that
 * failed changed nothing to show.
 */
fun worktreeMergesEnded(completedTasks: Flow<List<CompletedTask>>): Flow<CompletedTask> = completedTasks
    .drop(1)
    .mapNotNull { it.lastOrNull() }
    .distinctUntilChanged()
    .filter { it is CompletedTask.Success && it.taskType in WORKTREE_MERGES }
