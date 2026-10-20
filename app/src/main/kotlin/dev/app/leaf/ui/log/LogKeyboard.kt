// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.app.leaf.domain.log.LogMove
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding

/**
 * The selection move that a key press asks for in the log (fork-only), or null for any other key. Only the bare keys
 * count: with Ctrl, Cmd, Alt or Shift they are other shortcuts (Alt+arrows change the tab), or, with Shift, a range
 * selection that the log doesn't have. Left and Right do nothing: a row has nothing to step into.
 */
fun KeyEvent.logMove(): LogMove? {
    if (type != KeyEventType.KeyDown) return null

    return when {
        matchesBinding(KeybindingOption.UP) -> LogMove.UP
        matchesBinding(KeybindingOption.DOWN) -> LogMove.DOWN
        isAltPressed || isCtrlPressed || isMetaPressed || isShiftPressed -> null
        key == Key.PageUp -> LogMove.PAGE_UP
        key == Key.PageDown -> LogMove.PAGE_DOWN
        key == Key.MoveHome -> LogMove.HOME
        key == Key.MoveEnd -> LogMove.END
        else -> null
    }
}

/**
 * Scrolls the least that shows the row in full. A row only partly in view is moved in by its hidden part, so stepping
 * down the list scrolls it a row at a time instead of jumping, as `scrollToItem` would. A row far out of view is
 * placed at the start when it's above, and at the end when it's below, as Home, End and the pages go there.
 */
suspend fun LazyListState.bringRowIntoView(index: Int) {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.index == index }

    if (row == null) {
        val visible = info.visibleItemsInfo

        if (visible.isEmpty()) return

        if (index < visible.first().index) {
            scrollToItem(index)
        } else {
            val rowHeight = visible.first().size.coerceAtLeast(1)
            val rowsInView = ((info.viewportEndOffset - info.viewportStartOffset) / rowHeight).coerceAtLeast(1)

            scrollToItem((index - rowsInView + 1).coerceAtLeast(0))
        }

        return
    }

    val hiddenAbove = info.viewportStartOffset - row.offset
    val hiddenBelow = row.offset + row.size - info.viewportEndOffset

    when {
        hiddenAbove > 0 -> scrollBy(-hiddenAbove.toFloat())
        hiddenBelow > 0 -> scrollBy(hiddenBelow.toFloat())
    }
}

/** How many rows a page moves: what the list shows, less one, so the row that was last stays in view. */
fun LazyListState.pageRows(): Int = (layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
