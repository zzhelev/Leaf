// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.FailureSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** When the tab shows the uncommitted changes, after a worktree's branch was merged into the base. */
class WorktreeMergeResultsTest {
    @Test
    fun `follows each merge into the base that ends from now on, with or without conflicts`(): Unit = runBlocking {
        val earlier = CompletedTask.Success(1, TaskType.SquashMergeWorktree)
        val tasks = MutableStateFlow(listOf<CompletedTask>(earlier))
        val ended = mutableListOf<CompletedTask>()

        val job = launch { worktreeMergesEnded(tasks).take(2).toList(ended) }
        yield()

        val squash = CompletedTask.Success(2, TaskType.SquashMergeWorktree)
        val failed = CompletedTask.Failure(3, TaskType.MergeWorktree, GenericError("refused"), FailureSeverity.HIGH)
        val commit = CompletedTask.Success(4, TaskType.DoCommit)
        val merge = CompletedTask.Success(5, TaskType.MergeWorktree, stoppedAtConflicts = true)

        for (task in listOf(squash, failed, commit, merge)) {
            tasks.value = tasks.value + task
            yield()
        }

        job.join()
        assertEquals(listOf<CompletedTask>(squash, merge), ended)
    }
}
