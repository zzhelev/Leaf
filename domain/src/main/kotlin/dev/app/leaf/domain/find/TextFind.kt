// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.find

import dev.app.leaf.domain.log.SearchStep
import dev.app.leaf.domain.log.steppedSearchIndex

/** Find stops counting here, and its counter reads "10,000+" (upstream issues 275 and 312). */
const val MAX_FIND_MATCHES = 10_000

/**
 * A row of a text that find searches: a diff's or blame's list item, by its index there. [cells] are its texts as they
 * are drawn, one for unified diffs and blame, the old and the new side for a split diff, null where nothing is drawn
 * (a hunk's header, a side without a line). [oldLine] and [newLine] are the 1-based numbers that the row shows.
 */
data class FindRow(
    val cells: List<String?>,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

/** A match of find: characters [start] until [end] of [cell] in [row]. */
data class FindMatch(val row: Int, val cell: Int, val start: Int, val end: Int)

/** What find found: its matches in order, and whether it stopped at [MAX_FIND_MATCHES] with more left. */
data class FindResult(val matches: List<FindMatch>, val isCapped: Boolean) {
    companion object {
        val NONE = FindResult(emptyList(), isCapped = false)
    }
}

/**
 * Every match of [query] in [rows], as an editor finds them: row by row, a row's cells in order, left to right, a match
 * starting after the one before ends. Case is ignored unless [matchCase]. An empty query finds nothing.
 */
fun findMatches(
    rows: List<FindRow>,
    query: String,
    matchCase: Boolean,
    limit: Int = MAX_FIND_MATCHES,
): FindResult {
    if (query.isEmpty()) return FindResult.NONE

    val matches = ArrayList<FindMatch>()

    for ((rowIndex, row) in rows.withIndex()) {
        for ((cellIndex, text) in row.cells.withIndex()) {
            if (text == null || text.length < query.length) continue
            var from = 0

            while (from <= text.length - query.length) {
                val start = text.indexOf(query, from, ignoreCase = !matchCase)
                if (start < 0) break

                if (matches.size == limit) return FindResult(matches, isCapped = true)

                matches.add(FindMatch(rowIndex, cellIndex, start, start + query.length))
                from = start + query.length
            }
        }
    }

    return FindResult(matches, isCapped = false)
}

/**
 * The index of the match that find shows first: the first one at or after [row], the list's top row, as editors start
 * from where the reader is, or the first of them all when every match is above it. -1 without matches.
 */
fun firstMatchFrom(matches: List<FindMatch>, row: Int): Int {
    if (matches.isEmpty()) return -1
    val index = matches.indexOfFirst { it.row >= row }

    return if (index >= 0) index else 0
}

/**
 * The index of the match that takes [previous]'s place in [matches], found again after the text or the query changed:
 * the same one, or else the first after where it was, wrapping to the first. -1 without matches.
 */
fun matchKeptFrom(previous: FindMatch?, matches: List<FindMatch>): Int {
    if (matches.isEmpty()) return -1
    if (previous == null) return 0

    val index = matches.indexOfFirst { it.isAtOrAfter(previous) }

    return if (index >= 0) index else 0
}

private fun FindMatch.isAtOrAfter(other: FindMatch): Boolean = when {
    row != other.row -> row > other.row
    cell != other.cell -> cell > other.cell
    else -> start >= other.start
}

/**
 * The match after a step from [current], wrapping at both ends, as the log search's steps do. Indexes are 0-based
 * here, -1 for none.
 */
fun steppedMatch(current: Int, total: Int, step: SearchStep): Int =
    steppedSearchIndex(current + 1, total, step) - 1
