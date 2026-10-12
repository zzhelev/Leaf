// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import dev.app.leaf.domain.models.NotificationData
import dev.app.leaf.domain.models.NotificationType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The time it takes to notice that a toast appeared. */
private val NOTICE_TIME = 2.seconds

/** Reading time per character, about 16 characters a second. */
private val PER_CHARACTER = 60.milliseconds

private val MIN_POSITIVE = 4.seconds
private val MIN_WARNING = 6.seconds
private val MAX = 12.seconds

/**
 * How long a toast stays (fork-only): long enough to notice it and read it, so longer for a longer text, and at least
 * 6 s for a warning or an error, which may need acting on. Every toast used to go after 2 s, before a longer message
 * could be read. While the pointer is over a toast, it stays. A task without a toast is done at once.
 */
fun notificationDuration(data: NotificationData?): Duration {
    data ?: return Duration.ZERO

    val reading = NOTICE_TIME + PER_CHARACTER * data.content.length
    val min = if (data.type == NotificationType.Positive) MIN_POSITIVE else MIN_WARNING

    return reading.coerceIn(min, MAX)
}
