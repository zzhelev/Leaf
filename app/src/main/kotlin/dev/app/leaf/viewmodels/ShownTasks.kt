// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.repositories.CompletedTask

/**
 * The tasks whose toast or error dialog the tab has shown, once [task]'s has (fork-only): those of [shown] and [task]
 * that [completed], the tab's last completed tasks, still has.
 *
 * Upstream kept the last 50 of each list. A task is marked when its toast goes or its dialog is closed, which isn't
 * the order the tasks completed in: one without a toast is marked at once, a longer toast or one under the pointer
 * later, a dialog when the user closes it. So once a tab had had 50 tasks, a task that it still had could fall out of
 * the marked ones, and its toast showed again. Marking it pushed out the next, and old toasts played one after
 * another while the user worked.
 */
fun shownTasksAfter(
    shown: List<CompletedTask>,
    task: CompletedTask,
    completed: List<CompletedTask>,
): List<CompletedTask> {
    val kept = completed.toSet()

    return (shown + task).filter { it in kept }
}
