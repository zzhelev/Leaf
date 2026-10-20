// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

/** Which way the log search's buttons and keys move through the results (fork-only). */
enum class SearchStep { PREVIOUS, NEXT }

/**
 * The result that a step of the log search lands on. Indexes are 1-based, as the counter reads them ("3/12"), and 0
 * means no result.
 *
 * Both directions wrap: next from the last result goes to the first, previous from the first goes to the last. An
 * index outside the results counts as before the first for next, and after the last for previous, so a list that
 * changed under the index can't send a step out of range.
 */
fun steppedSearchIndex(current: Int, total: Int, step: SearchStep): Int = when {
    total <= 0 -> 0
    step == SearchStep.NEXT -> if (current !in 1 until total) 1 else current + 1
    else -> if (current !in 2..total) total else current - 1
}
