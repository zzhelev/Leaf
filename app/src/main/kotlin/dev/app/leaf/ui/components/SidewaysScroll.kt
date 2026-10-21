// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.theme.scrollbarHover
import dev.app.leaf.theme.scrollbarNormal
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far a notch of the mouse wheel, or a step of a sideways swipe, moves the lines. */
private val SidewaysStep = 48.dp

/** The space after the longest line when it's scrolled to its end. */
val SidewaysEndMargin = 24.dp

/**
 * Where lines that don't wrap are scrolled to sideways: one offset for all of them, so that the columns of the conflict
 * resolver and the sides of a split diff stay in step, as Meld keeps its panes. A column with shorter lines scrolls into
 * blank space. [max] lets the longest line, or the longest of the lines being typed in the resolver ([editorWidth]),
 * reach its end in the narrowest column ([viewport]), all in pixels.
 */
@Stable
class SidewaysScroll {
    var offset by mutableFloatStateOf(0f)
        private set
    var viewport by mutableFloatStateOf(0f)
        private set
    private var contentWidth by mutableFloatStateOf(0f)

    /** The longest line in the resolver's text field of the part being edited, with the margin after it, or 0. */
    var editorWidth by mutableFloatStateOf(0f)

    val max: Float get() = (maxOf(contentWidth, editorWidth) - viewport).coerceAtLeast(0f)

    fun scrollBy(delta: Float) = scrollTo(offset + delta)

    fun scrollTo(value: Float) {
        offset = value.coerceIn(0f, max)
    }

    /** The longest line is [contentWidth] wide, and the narrowest column shows [viewport] of it. */
    fun fit(contentWidth: Float, viewport: Float) {
        this.viewport = viewport.coerceAtLeast(0f)
        this.contentWidth = contentWidth
        offset = offset.coerceIn(0f, max)
    }
}

/** The lines' sideways scroll while they don't wrap, or null while they do. */
val LocalSidewaysScroll = compositionLocalOf<SidewaysScroll?> { null }

/**
 * Shows [content], a line of text whose own padding is [start] wide, on one row that scrolls sideways with [sideways]:
 * the gap of [start] stays put, and the content's own padding scrolls out of sight behind it.
 */
@Composable
fun UnwrappedLine(sideways: SidewaysScroll, start: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = start).clipToBounds()) {
        Box(UnwrappedElement(sideways, hiddenStart = start)) {
            content()
        }
    }
}

/**
 * Lays its content out on one line, as wide as it needs, and places it [SidewaysScroll.offset] to the left, and
 * [hiddenStart] more, which hides the content's own start. Placing reads the offset, so scrolling places the lines
 * again without measuring them.
 */
private class UnwrappedElement(
    private val sideways: SidewaysScroll,
    private val hiddenStart: Dp,
) : ModifierNodeElement<UnwrappedNode>() {
    override fun create() = UnwrappedNode(sideways, hiddenStart)

    override fun update(node: UnwrappedNode) {
        node.sideways = sideways
        node.hiddenStart = hiddenStart
    }

    override fun equals(other: Any?) =
        other is UnwrappedElement && other.sideways === sideways && other.hiddenStart == hiddenStart

    override fun hashCode() = 31 * System.identityHashCode(sideways) + hiddenStart.hashCode()
}

private class UnwrappedNode(var sideways: SidewaysScroll, var hiddenStart: Dp) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        val start = hiddenStart.roundToPx()

        return layout(width, placeable.height) {
            placeable.placeRelative(-sideways.offset.roundToInt() - start, 0)
        }
    }

    // One line high whatever the width: the rows' IntrinsicSize.Min asks with the column's width, which would wrap
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.minIntrinsicHeight(Constraints.Infinity)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.maxIntrinsicHeight(Constraints.Infinity)
}

/**
 * Scrolls the lines sideways on a sideways swipe or Shift and the wheel, before the lists see the event, so that they
 * only scroll up and down. A swipe that is more up and down than sideways stays the lists'.
 */
fun Modifier.scrollsSideways(sideways: SidewaysScroll?): Modifier {
    if (sideways == null) {
        return this
    }

    return pointerInput(sideways) {
        val step = SidewaysStep.toPx()

        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)

                if (event.type != PointerEventType.Scroll) {
                    continue
                }

                val change = event.changes.firstOrNull() ?: continue
                val delta = change.scrollDelta
                val amount = when {
                    abs(delta.x) > abs(delta.y) -> delta.x
                    event.keyboardModifiers.isShiftPressed -> delta.y
                    else -> 0f
                }

                if (amount != 0f) {
                    sideways.scrollBy(amount * step)
                    change.consume()
                }
            }
        }
    }
}

/** The sideways scrollbar, under the lines, while they don't wrap and the longest needs it. */
@Composable
fun SidewaysScrollbar(sideways: SidewaysScroll?, modifier: Modifier = Modifier) {
    if (sideways == null || sideways.max <= 0f) {
        return
    }

    val adapter = remember(sideways) { SidewaysScrollbarAdapter(sideways) }

    HorizontalScrollbar(
        adapter = adapter,
        modifier = modifier.fillMaxWidth().padding(bottom = 2.dp),
        style = LocalScrollbarStyle.current.copy(
            unhoverColor = MaterialTheme.colors.scrollbarNormal,
            hoverColor = MaterialTheme.colors.scrollbarHover,
        ),
    )
}

private class SidewaysScrollbarAdapter(private val sideways: SidewaysScroll) : ScrollbarAdapter {
    override val scrollOffset get() = sideways.offset.toDouble()
    override val contentSize get() = (sideways.viewport + sideways.max).toDouble()
    override val viewportSize get() = sideways.viewport.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) = sideways.scrollTo(scrollOffset.toFloat())
}
