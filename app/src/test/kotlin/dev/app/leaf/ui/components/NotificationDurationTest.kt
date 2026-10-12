// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import dev.app.leaf.domain.models.NotificationData
import dev.app.leaf.domain.models.NotificationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long toasts stay: long enough to read them (fork-only). */
class NotificationDurationTest {
    @Test
    fun `a short toast stays 4 s, and a short warning or error 6 s`() {
        assertEquals(4.seconds, notificationDuration(positive("Conflict resolved")))
        assertEquals(6.seconds, notificationDuration(NotificationData(NotificationType.Warning, "Stopped")))
        assertEquals(6.seconds, notificationDuration(NotificationData(NotificationType.Error, "Failed")))
    }

    @Test
    fun `a longer text stays longer, up to 12 s`() {
        // 2 s to notice it, then 60 ms a character
        assertEquals(2.seconds + 60.milliseconds * 50, notificationDuration(positive("x".repeat(50))))
        assertEquals(12.seconds, notificationDuration(positive("x".repeat(500))))
    }

    @Test
    fun `a task without a toast is done at once`() {
        assertEquals(Duration.ZERO, notificationDuration(null))
    }

    private fun positive(text: String) = NotificationData(NotificationType.Positive, text)
}
