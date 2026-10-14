// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** After how many days without a change a worktree shows as idle ([AppConfig.WorktreesUntouchedDays]). */
object WorktreesUntouchedDays {
    const val DEFAULT_DAYS = 14

    /** What the settings offer. 0 turns the marker off. */
    val CHOICES = listOf(0, 7, 14, 30, 90)

    /**
     * The fewest days that count, whatever the settings file says: the worktree list only looks at the changed files of
     * a worktree whose git dir hasn't changed for this long, and an earlier change in them would go unseen.
     */
    const val MIN_DAYS = 7

    /** The days after which a worktree shows as idle, or null when the marker is off. */
    fun idleAfterDays(days: Int): Int? = if (days <= 0) null else days.coerceAtLeast(MIN_DAYS)
}
