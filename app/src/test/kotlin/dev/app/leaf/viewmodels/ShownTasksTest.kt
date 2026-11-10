// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.MAX_COMPLETED_TASKS_KEPT
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.FailureSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The toasts and error dialogs that a tab has shown. The tab keeps its last [MAX_COMPLETED_TASKS_KEPT] completed tasks,
 * and shows a toast for each that it hasn't marked as shown, as `RepositoryTabViewModel.notifications` does.
 */
class ShownTasksTest {
    /** A tab's completed tasks and the ones it marked, as the repository and the view model keep them. */
    private class Tab {
        var completed = emptyList<CompletedTask>()
        var shown = emptyList<CompletedTask>()
        private var date = 0L

        fun complete(type: TaskType = TaskType.StageFile): CompletedTask {
            val task = CompletedTask.Success(date++, type)
            completed = (completed + task).takeLast(MAX_COMPLETED_TASKS_KEPT)
            return task
        }

        fun fail(): CompletedTask {
            val task = CompletedTask.Failure(date++, TaskType.PullFromBranch, GenericError("refused"), FailureSeverity.HIGH)
            completed = (completed + task).takeLast(MAX_COMPLETED_TASKS_KEPT)
            return task
        }

        fun markShown(task: CompletedTask) {
            shown = shownTasksAfter(shown, task, completed)
        }

        /** What the tab shows: its completed tasks that it hasn't marked as shown. */
        fun waiting() = completed - shown.toSet()
    }

    @Test
    fun `a toast that goes after a later task's doesn't come back once 50 more tasks completed`() {
        val tab = Tab()
        val rebase = tab.complete(TaskType.RebaseBranch)
        val stage = tab.complete()

        // The stage has no toast, so it's marked at once, while the rebase's toast is still up
        tab.markShown(stage)
        tab.markShown(rebase)

        repeat(80) {
            val task = tab.complete()
            tab.markShown(task)

            assertEquals(emptyList<CompletedTask>(), tab.waiting(), "after ${it + 1} more tasks")
        }
    }

    @Test
    fun `an error dialog accepted after later tasks doesn't bring back old toasts`() {
        val tab = Tab()
        val error = tab.fail()

        repeat(10) { tab.markShown(tab.complete()) }
        tab.markShown(error)

        repeat(80) {
            tab.markShown(tab.complete())

            assertEquals(emptyList<CompletedTask>(), tab.waiting(), "after ${it + 1} more tasks")
        }
    }

    @Test
    fun `a task that was shown never shows again, whatever order the toasts go in`() {
        for (seed in 0 until 50) {
            val random = Random(seed)
            val tab = Tab()
            val everShown = mutableSetOf<CompletedTask>()
            // Each waiting task's toast goes at a time of its own: at once without a toast, later for a long one
            val goesAt = mutableMapOf<CompletedTask, Int>()

            for (tick in 0 until 2_000) {
                if (tick < 1_500 && random.nextInt(4) == 0) tab.complete()

                for (task in tab.waiting()) {
                    assertTrue(task !in everShown, "seed $seed, tick $tick: $task was shown again")
                    goesAt.getOrPut(task) { tick + listOf(0, 0, 8, 10, 12, 24, 60).random(random) }
                }

                for ((task, at) in goesAt.entries.filter { it.value <= tick }) {
                    goesAt.remove(task)
                    everShown += task
                    tab.markShown(task)
                }
            }

            assertEquals(emptyList<CompletedTask>(), tab.waiting(), "seed $seed")
        }
    }

    @Test
    fun `it keeps only the tasks that the tab still has`() {
        val tab = Tab()

        repeat(300) { tab.markShown(tab.complete()) }

        assertEquals(tab.completed, tab.shown)
    }
}
