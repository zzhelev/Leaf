// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

/** One HEAD reflog entry: its message and when it was written. */
data class ReflogLine(val comment: String, val timeMillis: Long)

private val checkoutRegex = Regex("""^checkout: moving from (\S+) to (\S+)$""")

/**
 * The most recent time each branch was checked out, from HEAD reflog entries such as
 * `checkout: moving from main to feature/x` (written by both `git checkout` and `git switch`). Keys are short branch
 * names. Detached checkouts produce commit ids as keys, which match no branch.
 */
fun lastCheckoutTimes(entries: List<ReflogLine>): Map<String, Long> {
    val result = HashMap<String, Long>()

    for (entry in entries) {
        val match = checkoutRegex.matchEntire(entry.comment.trim()) ?: continue
        val target = match.groupValues[2]
        val previous = result[target]

        if (previous == null || entry.timeMillis > previous) {
            result[target] = entry.timeMillis
        }
    }

    return result
}
