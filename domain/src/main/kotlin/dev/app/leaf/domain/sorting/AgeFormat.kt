// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import kotlin.math.roundToLong

private const val DAY_IN_MS = 24 * 60 * 60 * 1000L

/**
 * Compact age of [timeMillis] relative to [nowMillis]: `now` (under a day), `<n>d` (under a week), `<n>w` (under 60
 * days) or `<n>mo`. Times in the future count as `now`.
 */
fun formatAge(timeMillis: Long, nowMillis: Long): String {
    val days = ((nowMillis - timeMillis) / DAY_IN_MS).coerceAtLeast(0)

    return when {
        days == 0L -> "now"
        days < 7 -> "${days}d"
        days < 60 -> "${(days / 7.0).roundToLong()}w"
        else -> "${(days / 30.0).roundToLong()}mo"
    }
}

private const val MINUTE_IN_MS = 60 * 1000L
private const val HOUR_IN_MS = 60 * MINUTE_IN_MS

/**
 * How long ago [timeMillis] was, for a sentence such as "Last fetched 4 min ago" (fork-only, follows upstream issue
 * 116): `just now` (under a minute), `<n> min ago`, `<n> h ago` (under a day), then [formatAge]'s days, weeks and
 * months with ` ago`. Times in the future count as `just now`.
 */
fun formatTimeAgo(timeMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - timeMillis).coerceAtLeast(0)

    return when {
        elapsed < MINUTE_IN_MS -> "just now"
        elapsed < HOUR_IN_MS -> "${elapsed / MINUTE_IN_MS} min ago"
        elapsed < DAY_IN_MS -> "${elapsed / HOUR_IN_MS} h ago"
        else -> "${formatAge(timeMillis, nowMillis)} ago"
    }
}
