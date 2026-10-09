// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How often the worktree list refreshes while it's on screen ([AppConfig.WorktreesRefreshInterval]), in seconds. */
object WorktreesRefreshIntervals {
    const val DEFAULT_SECONDS = 10

    /** What the settings offer. 0 turns the refresh off. */
    val CHOICES = listOf(0, 5, 10, 30, 60)

    /** Refreshes never come closer than this, whatever the settings file says. */
    private const val MIN_SECONDS = 2

    /** The time between two refreshes, or null when they're off. */
    fun pollInterval(seconds: Int): Duration? = if (seconds <= 0) null else seconds.coerceAtLeast(MIN_SECONDS).seconds
}
