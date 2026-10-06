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
