// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.tabs_scroll_back
import dev.app.leaf.app.generated.resources.tabs_scroll_forward
import dev.app.leaf.extensions.handOnHover
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The part of the title bar at its right end that holds no tab, so that the window can always be moved from there
 * (option 1, chosen with the user on 2026-11-04).
 */
val TITLE_BAR_FREE_STRIP = 110.dp

/** How narrow a tab gets in the title bar before the tabs scroll. */
val TITLE_BAR_MIN_TAB_WIDTH = 120.dp

/** The + button after the tabs. */
val ADD_TAB_BUTTON_WIDTH = 36.dp

private val SCROLL_ARROW_WIDTH = 24.dp

/** Whether [tabCount] tabs fit in [width] at [TITLE_BAR_MIN_TAB_WIDTH] each, with the + button after them. */
fun tabsFitTitleBar(width: Dp, tabCount: Int): Boolean =
    TITLE_BAR_MIN_TAB_WIDTH * tabCount <= width - ADD_TAB_BUTTON_WIDTH

/**
 * The most that each tab may take: an equal share of [width] when the tabs don't fit at their own widths, but never
 * less than [TITLE_BAR_MIN_TAB_WIDTH]. Below that, the tabs scroll.
 */
fun titleBarTabWidth(width: Dp, tabCount: Int): Dp {
    if (tabCount == 0) return width

    return maxOf(TITLE_BAR_MIN_TAB_WIDTH, (width - ADD_TAB_BUTTON_WIDTH) / tabCount)
}

/** Scrolls the tabs by most of what shows, back or [forward]; dimmed at that end. */
@Composable
fun TabScrollArrow(state: LazyListState, forward: Boolean) {
    val scope = rememberCoroutineScope()
    val canScroll = if (forward) state.canScrollForward else state.canScrollBackward
    val label = stringResource(if (forward) Res.string.tabs_scroll_forward else Res.string.tabs_scroll_back)

    IconButton(
        onClick = {
            val distance = state.layoutInfo.viewportSize.width * 0.8f

            scope.launch {
                state.animateScrollBy(if (forward) distance else -distance)
            }
        },
        enabled = canScroll,
        modifier = Modifier
            .size(width = SCROLL_ARROW_WIDTH, height = ADD_TAB_BUTTON_WIDTH)
            .run { if (canScroll) handOnHover() else this },
    ) {
        Icon(
            imageVector = if (forward) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
            contentDescription = label,
            tint = MaterialTheme.colors.onBackground.copy(alpha = if (canScroll) 1f else 0.3f),
        )
    }
}
