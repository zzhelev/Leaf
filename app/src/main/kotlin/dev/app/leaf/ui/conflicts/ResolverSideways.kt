// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.extensions.replaceTabs
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.scrollbarHover
import dev.app.leaf.theme.scrollbarNormal
import dev.app.leaf.ui.diff.DiffText
import dev.app.leaf.ui.diff.MAX_DIFF_LINE_CHARACTERS
import dev.app.leaf.ui.diff.syntax_highlighter.SyntaxHighlighter
import org.eclipse.jgit.diff.RawText
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far a notch of the mouse wheel, or a step of a sideways swipe, moves the lines. */
private val SidewaysStep = 48.dp

/**
 * The space that [DiffText] leaves before a line, which stays between the line numbers and the text while it scrolls,
 * and the space after the longest line when it's scrolled to its end.
 */
private val LineStartPadding = 16.dp
private val LineEndMargin = 24.dp

/**
 * Where the resolver's lines are scrolled to sideways while they don't wrap: one offset for every column of both
 * layouts, as Meld keeps its panes in step. A column with shorter lines scrolls into blank space. [max] lets the longest
 * line of the file, or of the lines being typed ([editorWidth]), reach its end in the narrowest column ([viewport]),
 * all in pixels.
 */
@Stable
class SidewaysScroll {
    var offset by mutableFloatStateOf(0f)
        private set
    var viewport by mutableFloatStateOf(0f)
        private set
    private var contentWidth by mutableFloatStateOf(0f)

    /** The longest line in the text field of the part being edited, with the margin after it, or 0. */
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
internal val LocalSidewaysScroll = compositionLocalOf<SidewaysScroll?> { null }

/**
 * A line's text: wrapped, or on one row that scrolls sideways with the others ([LocalSidewaysScroll]), with [marks] on
 * the words that its side changed.
 */
@Composable
internal fun LineText(text: String, syntaxHighlighter: SyntaxHighlighter, marks: LineMarks? = null) {
    val sideways = LocalSidewaysScroll.current
    val matchLine = remember(text, marks) { marks?.matchLine(text) }
    val markColor = marks?.color ?: Color.Transparent

    if (sideways == null) {
        DiffText(text, matchLine, syntaxHighlighter, insertedColor = markColor)
    } else {
        // The gap before the text stays put, and DiffText's own padding scrolls out of sight behind it
        Box(Modifier.fillMaxWidth().padding(start = LineStartPadding).clipToBounds()) {
            Box(UnwrappedElement(sideways, hiddenStart = LineStartPadding)) {
                DiffText(text, matchLine, syntaxHighlighter, insertedColor = markColor)
            }
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
internal fun Modifier.scrollsSideways(sideways: SidewaysScroll?): Modifier {
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

/**
 * Fits [sideways] to the longest line of [merge], in the narrowest column, [narrowestColumn] wide, whose line numbers
 * have [numberDigits] digits.
 */
@Composable
internal fun FitSideways(sideways: SidewaysScroll?, merge: TextMerge, narrowestColumn: Dp, numberDigits: Int) {
    if (sideways == null) {
        return
    }

    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography())
    val longest = remember(merge) { longestLine(merge) }
    val lineWidth = remember(longest, style) { measurer.measure(longest, style, softWrap = false).size.width }
    // As LineNumber lays them out: the digits, with 8 dp before and 4 after
    val numbersWidth = remember(numberDigits, style) {
        measurer.measure("0".repeat(numberDigits), style, softWrap = false).size.width
    }
    val density = LocalDensity.current

    SideEffect {
        with(density) {
            sideways.fit(
                contentWidth = lineWidth + LineEndMargin.toPx(),
                viewport = narrowestColumn.toPx() - numbersWidth - (12.dp + LineStartPadding).toPx(),
            )
        }
    }
}

/** The longest line of the three versions, as [DiffText] shows it: with tabs as spaces, and cut where it cuts. */
private fun longestLine(merge: TextMerge): String {
    var longest = ""

    for (text in listOf(merge.base, merge.ours, merge.theirs)) {
        val (line, length) = longestLineOf(text) ?: continue

        if (length > longest.length) {
            longest = text.getString(line).replaceTabs()
        }
    }

    // DiffText ends a longer line with a note of what it left out, such as "1,234 more characters"
    return if (longest.length > MAX_DIFF_LINE_CHARACTERS) {
        longest.take(MAX_DIFF_LINE_CHARACTERS) + " 1,000,000 more characters"
    } else {
        longest
    }
}

/**
 * The index and length of [text]'s longest line, counted in characters with tabs as four spaces, from its bytes, which
 * RawText splits at each LF. Null for a text without lines.
 */
private fun longestLineOf(text: RawText): Pair<Int, Int>? {
    if (text.size() == 0) {
        return null
    }

    var longest = 0 to 0
    var line = 0
    var length = 0

    for (byte in text.rawContent) {
        when (val code = byte.toInt() and 0xFF) {
            '\n'.code -> {
                if (length > longest.second) longest = line to length
                line++
                length = 0
            }

            '\t'.code -> length += 4
            '\r'.code -> Unit
            // Not the first byte of a UTF-8 character
            else -> if (code and 0xC0 != 0x80) length++
        }
    }

    // The last line, without a line ending
    if (line < text.size() && length > longest.second) {
        longest = line to length
    }

    return longest
}

/** The sideways scrollbar, under the lines, while they don't wrap and the longest needs it. */
@Composable
internal fun SidewaysScrollbar(sideways: SidewaysScroll?, modifier: Modifier = Modifier) {
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
