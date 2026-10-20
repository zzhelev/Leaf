// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.ui.SelectedItem

/** How the keyboard moves the log's selection (fork-only). */
enum class LogMove { UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END }

/**
 * The row that a key moves the selection to, or null when it stays where it is: no rows, or already at the end that
 * the key goes to. Rows are counted from 0, the uncommitted changes' row first when the log has one.
 *
 * The selection never wraps: the log can hold tens of thousands of commits, and Down on the last loaded one that
 * jumps to the top would be a surprise. Without a selected row (`current` is null, or outside the rows, as when the
 * selected commit isn't loaded) the first key press selects the first row, and End the last.
 * A page is `pageSize` rows, at least 1.
 */
fun movedLogRow(current: Int?, rowCount: Int, move: LogMove, pageSize: Int): Int? {
    if (rowCount <= 0) return null

    val last = rowCount - 1
    val from = current?.takeIf { it in 0..last }
    val page = pageSize.coerceAtLeast(1)

    val target = when {
        from == null -> if (move == LogMove.END) last else 0
        else -> when (move) {
            LogMove.UP -> from - 1
            LogMove.DOWN -> from + 1
            LogMove.PAGE_UP -> from - page
            LogMove.PAGE_DOWN -> from + page
            LogMove.HOME -> 0
            LogMove.END -> last
        }.coerceIn(0, last)
    }

    return target.takeIf { it != from }
}

/**
 * The row that a key moves the log's selection to, given what is selected (see [movedLogRow]). Rows are the
 * uncommitted changes' row, when the log has one, then the commits in the order of `commitHashes`.
 *
 * What counts as the current row: the uncommitted changes when selected and shown, and the row of a selected commit,
 * branch or tag. Anything else (nothing, a worktree review, a commit that isn't loaded) is no row.
 */
fun movedLogSelection(
    selected: SelectedItem,
    commitHashes: List<String>,
    hasUncommittedRow: Boolean,
    move: LogMove,
    pageSize: Int,
): Int? {
    val offset = if (hasUncommittedRow) 1 else 0

    val current = when (selected) {
        SelectedItem.UncommittedChanges -> 0.takeIf { hasUncommittedRow }
        is SelectedItem.CommitBasedItem -> commitHashes.indexOf(selected.commit.hash).takeIf { it >= 0 }?.plus(offset)
        else -> null
    }

    return movedLogRow(current, commitHashes.size + offset, move, pageSize)
}
