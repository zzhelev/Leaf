// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

/** What a column of the Columns layout keeps when a gutter is dragged, in dp. */
const val MIN_COLUMN_WIDTH = 120f

/** The conflicted files' list, in dp: its width until it's dragged, and how far it goes. */
const val DEFAULT_FILE_LIST_WIDTH = 220f
const val MIN_FILE_LIST_WIDTH = 140f
const val MAX_FILE_LIST_WIDTH = 480f

/**
 * The widths of the Columns layout's ours, result and theirs, as shares of the width that the gutters leave. Theirs has
 * what the other two leave. Dragging a gutter moves the edge between its two columns.
 */
data class ColumnShares(val ours: Float = 1f / 3, val result: Float = 1f / 3) {
    val theirs: Float get() = 1f - ours - result

    /** The gutter between ours and the result moved by [delta], a share of the width: neither goes below [min]. */
    fun movingOursGutter(delta: Float, min: Float): ColumnShares {
        val moved = delta.between(left = ours, right = result, min = min)

        return copy(ours = ours + moved, result = result - moved)
    }

    /** The gutter between the result and theirs moved by [delta]: neither goes below [min]. */
    fun movingTheirsGutter(delta: Float, min: Float): ColumnShares {
        val moved = delta.between(left = result, right = theirs, min = min)

        return copy(result = result + moved)
    }
}

/**
 * How far an edge between [left] and [right] moves for [this]: neither column goes below [min], and one that is
 * already below it, as after the window got narrower, doesn't shrink.
 */
private fun Float.between(left: Float, right: Float, min: Float): Float =
    coerceIn(minOf(min - left, 0f), maxOf(right - min, 0f))

/** The file list's width after its edge moved by [delta] dp. */
fun fileListWidthAfterDrag(width: Float, delta: Float): Float =
    (width + delta).coerceIn(MIN_FILE_LIST_WIDTH, MAX_FILE_LIST_WIDTH)
