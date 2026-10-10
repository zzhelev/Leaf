// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.models.NotificationData
import dev.app.leaf.domain.models.NotificationType
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The toast of a completed task ([toNotificationData]): one that stopped at conflicts warns, not "completed". */
class CompletedTaskNotificationTest {
    @Test
    fun `a merge, rebase or pull that stopped at conflicts warns`() {
        assertEquals(
            NotificationData(NotificationType.Warning, "Merge stopped at conflicts, fix them to continue"),
            stoppedAtConflicts(TaskType.MergeBranch),
        )
        assertEquals(
            NotificationData(NotificationType.Warning, "Rebase stopped at conflicts, fix them to continue"),
            stoppedAtConflicts(TaskType.RebaseBranch),
        )
        assertEquals(
            NotificationData(NotificationType.Warning, "Pull stopped at conflicts, fix them to continue"),
            stoppedAtConflicts(TaskType.Pull),
        )
    }

    @Test
    fun `one that completed still says so`() {
        assertEquals(NotificationData(NotificationType.Positive, "Merge completed"), completed(TaskType.MergeBranch))
        assertEquals(NotificationData(NotificationType.Positive, "Rebase completed"), completed(TaskType.RebaseBranch))
        assertEquals(NotificationData(NotificationType.Positive, "Pull completed"), completed(TaskType.Pull))
    }

    private fun stoppedAtConflicts(taskType: TaskType) =
        CompletedTask.Success(date = 0, taskType, stoppedAtConflicts = true).toNotificationData()

    private fun completed(taskType: TaskType) = CompletedTask.Success(date = 0, taskType).toNotificationData()
}
