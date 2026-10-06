// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 1_800_000_000_000L

class AgeAndReflogTest {
    @Test
    fun `formats ages in days, weeks and months`() {
        assertEquals("now", formatAge(NOW, NOW))
        assertEquals("now", formatAge(NOW - DAY + 1, NOW))
        assertEquals("1d", formatAge(NOW - DAY, NOW))
        assertEquals("6d", formatAge(NOW - 6 * DAY, NOW))
        assertEquals("1w", formatAge(NOW - 7 * DAY, NOW))
        assertEquals("2w", formatAge(NOW - 11 * DAY, NOW))
        assertEquals("8w", formatAge(NOW - 59 * DAY, NOW))
        assertEquals("2mo", formatAge(NOW - 60 * DAY, NOW))
        assertEquals("12mo", formatAge(NOW - 365 * DAY, NOW))
    }

    @Test
    fun `a time in the future counts as now`() {
        assertEquals("now", formatAge(NOW + 5 * DAY, NOW))
    }

    @Test
    fun `takes the latest checkout of each branch from the reflog`() {
        val entries = listOf(
            ReflogLine("checkout: moving from main to feature/a", 3000),
            ReflogLine("commit: Add things", 3500),
            ReflogLine("checkout: moving from feature/a to main", 2000),
            ReflogLine("checkout: moving from main to feature/a", 1000),
            ReflogLine("checkout: moving from develop to main", 500),
            ReflogLine("reset: moving to HEAD~1", 400),
            ReflogLine("checkout: moving from main to 1a2b3c4d5e6f", 300),
        )

        assertEquals(
            mapOf("feature/a" to 3000L, "main" to 2000L, "1a2b3c4d5e6f" to 300L),
            lastCheckoutTimes(entries),
        )
    }

    @Test
    fun `ignores entries that are not checkouts`() {
        val entries = listOf(
            ReflogLine("commit (initial): Initial commit", 100),
            ReflogLine("Branch: renamed refs/heads/a to refs/heads/b", 200),
            ReflogLine("merge feature: Fast-forward", 300),
        )

        assertEquals(emptyMap<String, Long>(), lastCheckoutTimes(entries))
    }
}
