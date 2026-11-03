// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.menu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.scrollbarHover
import dev.app.leaf.theme.scrollbarNormal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** How far a menu stays from the window's edges: it never touches them, and a click beside it can still close it. */
val MENU_WINDOW_MARGIN = 8.dp

/** The fades at the ends of a menu that scrolls, which say that it goes on, and how far the keys keep an entry from them. */
internal val MENU_FADE_HEIGHT = 16.dp

private val SCROLLBAR_WIDTH = 4.dp
private val SCROLLBAR_INSET = 3.dp
private val SCROLLBAR_MIN_LENGTH = 24.dp

/** How wide the strip at a scrolling menu's right edge is where a press drags the scrollbar rather than picks an entry. */
private val SCROLLBAR_STRIP_WIDTH = 10.dp

/** How much wider a menu gets while it scrolls, so that its entries' ends stay clear of the scrollbar. */
private val SCROLLBAR_ROOM = 6.dp

/**
 * Where a menu that opens at [point] goes: there when it fits to the right and below, else moved left or up just
 * enough to fit, [margin] from the window's edges. A menu taller than the window less its margins is cut to that
 * height ([fitsWindowHeight]), so it starts at the top margin.
 */
fun menuPositionAt(point: IntOffset, windowSize: IntSize, menuSize: IntSize, margin: Int) = IntOffset(
    x = fittedStart(point.x, menuSize.width, windowSize.width, margin),
    y = fittedStart(point.y, menuSize.height, windowSize.height, margin),
)

/**
 * Where a menu of [size] starts along an [extent] of the window: at [preferred] when it ends [margin] before the
 * extent's end or sooner, else as far back as that needs, and never before [margin] while it fits between the two.
 */
internal fun fittedStart(preferred: Int, size: Int, extent: Int, margin: Int): Int {
    val last = extent - margin - size

    return preferred.coerceAtMost(last).coerceAtLeast(min(margin, max(last, 0)))
}

/**
 * The top of a menu that opens under a button, at [below], or over it, ending at [aboveEnd], when it doesn't fit under
 * it. When it fits neither way, it's moved up from below just enough to fit, [margin] from the window's edges.
 */
fun menuTopBelowOrAbove(below: Int, aboveEnd: Int, height: Int, windowHeight: Int, margin: Int): Int = when {
    below + height <= windowHeight - margin -> below
    aboveEnd - height >= margin -> aboveEnd - height
    else -> fittedStart(below, height, windowHeight, margin)
}

/**
 * Cuts the menu to the window's height less [margin] at each end. A popup is measured with the window's size as its
 * constraints, so the menu can't be taller than the window; this keeps its edges off the window's.
 */
fun Modifier.fitsWindowHeight(margin: Dp = MENU_WINDOW_MARGIN) = layout { measurable, constraints ->
    val fitted = if (constraints.hasBoundedHeight) {
        val cap = (constraints.maxHeight - 2 * margin.roundToPx()).coerceAtLeast(constraints.minHeight)
        constraints.copy(maxHeight = cap)
    } else {
        constraints
    }
    val placeable = measurable.measure(fitted)

    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Where a scrollbar's thumb is in a [viewport] that scrolls by up to [maxScroll], at [scroll]: its top and length. */
internal data class ScrollbarThumb(val top: Float, val length: Float)

internal fun scrollbarThumb(viewport: Float, maxScroll: Float, scroll: Float, inset: Float, minLength: Float): ScrollbarThumb {
    val track = (viewport - 2 * inset).coerceAtLeast(0f)
    val length = (track * viewport / (viewport + maxScroll)).coerceIn(min(minLength, track), track)
    val travel = track - length
    val top = inset + if (maxScroll > 0f) travel * (scroll / maxScroll).coerceIn(0f, 1f) else 0f

    return ScrollbarThumb(top, length)
}

/**
 * A menu's entries, which scroll when the menu is cut to the window ([fitsWindowHeight]). While they do, a scrollbar
 * that can be dragged sits at the right edge, and a fade at each end that has more entries beyond it. An entry that
 * the keys move to is kept clear of the fades. [contentPadding] scrolls with the entries.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MenuScrollColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val fade = with(LocalDensity.current) { MENU_FADE_HEIGHT.toPx() }
    val clearOfFades = remember(fade) { ClearOfFades(fade) }
    // Known once the entries are measured: the menu then widens by a frame later, which keeps the longest entry's end
    // off the scrollbar
    val scrolls by remember(scrollState) { derivedStateOf { scrolls(scrollState.maxValue) } }

    CompositionLocalProvider(LocalBringIntoViewSpec provides clearOfFades) {
        Column(
            modifier = modifier
                .menuScrollIndicators(scrollState)
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(end = if (scrolls) SCROLLBAR_ROOM else 0.dp),
            content = content,
        )
    }
}

/** Scrolls an entry that takes the focus into view [fade] clear of the menu's ends, or to its top when it can't be. */
@OptIn(ExperimentalFoundationApi::class)
private class ClearOfFades(private val fade: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - fade
        val end = offset + size + fade

        return when {
            start < 0f -> start
            end > containerSize -> min(end - containerSize, start)
            else -> 0f
        }
    }
}

/** The fades, the scrollbar and its dragging, drawn over the viewport rather than the entries that scroll. */
@Composable
private fun Modifier.menuScrollIndicators(state: ScrollState): Modifier {
    val density = LocalDensity.current
    val fadeColor = MaterialTheme.colors.background
    val normal = MaterialTheme.colors.scrollbarNormal
    val hover = MaterialTheme.colors.scrollbarHover
    var isDragging by remember { mutableStateOf(false) }

    val fade = with(density) { MENU_FADE_HEIGHT.toPx() }
    val width = with(density) { SCROLLBAR_WIDTH.toPx() }
    val inset = with(density) { SCROLLBAR_INSET.toPx() }
    val minLength = with(density) { SCROLLBAR_MIN_LENGTH.toPx() }
    val strip = with(density) { SCROLLBAR_STRIP_WIDTH.toPx() }

    return this
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val maxScroll = state.maxValue

                if (!scrolls(maxScroll) || down.position.x < size.width - strip) {
                    return@awaitEachGesture
                }

                down.consume()
                isDragging = true

                val viewport = size.height.toFloat()
                val thumb = scrollbarThumb(viewport, maxScroll.toFloat(), state.value.toFloat(), inset, minLength)
                val onThumb = down.position.y in thumb.top..(thumb.top + thumb.length)

                if (!onThumb) {
                    // A press on the track pages towards it, as a scrollbar's track does
                    state.dispatchRawDelta(sign(down.position.y - thumb.top) * (viewport - 2 * fade))
                }

                val travel = (viewport - 2 * inset - thumb.length).coerceAtLeast(1f)

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            change.consume()
                            break
                        }

                        val dy = change.positionChange().y

                        if (onThumb && abs(dy) > 0f) {
                            state.dispatchRawDelta(dy * maxScroll / travel)
                        }

                        change.consume()
                    }
                } finally {
                    isDragging = false
                }
            }
        }
        .drawWithContent {
            drawContent()

            val maxScroll = state.maxValue

            if (!scrolls(maxScroll)) {
                return@drawWithContent
            }

            val scroll = state.value

            if (scroll > 0) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(fadeColor, Color.Transparent), startY = 0f, endY = fade),
                    size = Size(size.width, fade),
                )
            }

            if (scroll < maxScroll) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, fadeColor),
                        startY = size.height - fade,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fade),
                    size = Size(size.width, fade),
                )
            }

            val thumb = scrollbarThumb(size.height, maxScroll.toFloat(), scroll.toFloat(), inset, minLength)

            drawRoundRect(
                color = if (isDragging) hover else normal,
                topLeft = Offset(size.width - inset - width, thumb.top),
                size = Size(width, thumb.length),
                cornerRadius = CornerRadius(width / 2),
            )
        }
}

/** A scroll state's maximum before it has been measured is [Int.MAX_VALUE]: it doesn't scroll yet. */
private fun scrolls(maxScroll: Int) = maxScroll in 1 until Int.MAX_VALUE

/**
 * The keys that move through a menu whose entries take the focus: ↑ and ↓ to the entry before or after, Home and End
 * to the [first] and the [last] entry that can be picked. The focused entry scrolls into view ([MenuScrollColumn]).
 */
fun KeyEvent.movesInMenu(focusManager: FocusManager, first: FocusRequester?, last: FocusRequester?): Boolean {
    if (type != KeyEventType.KeyDown) {
        return false
    }

    val isBare = !isShiftPressed && !isCtrlPressed && !isAltPressed && !isMetaPressed

    return when {
        matchesBinding(KeybindingOption.DOWN) -> focusManager.moveFocus(FocusDirection.Next)
        matchesBinding(KeybindingOption.UP) -> focusManager.moveFocus(FocusDirection.Previous)
        isBare && key == Key.MoveHome && first != null -> first.requestFocus().let { true }
        isBare && key == Key.MoveEnd && last != null -> last.requestFocus().let { true }
        else -> false
    }
}
