// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * How far a list has to scroll for an item laid out from [itemStart] to [itemEnd] to show whole in a viewport from
 * [viewportStart] to [viewportEnd]: 0 when it already does, negative towards the list's start. An item longer than the
 * viewport lines up with its start.
 */
fun revealScrollDelta(itemStart: Int, itemEnd: Int, viewportStart: Int, viewportEnd: Int): Int = when {
    itemStart < viewportStart -> itemStart - viewportStart
    itemEnd - itemStart >= viewportEnd - viewportStart -> itemStart - viewportStart
    itemEnd > viewportEnd -> itemEnd - viewportEnd
    else -> 0
}

/**
 * Scrolls the list the least that shows the item at [index] whole. One that is cut at an edge slides in; one out of sight
 * jumps in, at the edge it was beyond, as stepping through the items would bring it.
 */
suspend fun LazyListState.revealItem(index: Int) {
    if (index !in 0 until layoutInfo.totalItemsCount) return

    val shown = layoutInfo.visibleItemsInfo

    if (shown.none { it.index == index }) {
        val isBeyondEnd = index > (shown.lastOrNull()?.index ?: -1)
        // Lays the list out again at once, with the item at the start, or as near it as the list's end allows
        scrollToItem(index)

        if (isBeyondEnd) {
            val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
            val towardsEnd = item.offset + item.size - layoutInfo.viewportEndOffset

            if (towardsEnd < 0) {
                scrollBy(towardsEnd.toFloat())
            }
        }

        return
    }

    val item = shown.first { it.index == index }
    val delta = revealScrollDelta(
        itemStart = item.offset,
        itemEnd = item.offset + item.size,
        viewportStart = layoutInfo.viewportStartOffset,
        viewportEnd = layoutInfo.viewportEndOffset,
    )

    if (delta != 0) {
        animateScrollBy(delta.toFloat())
    }
}
