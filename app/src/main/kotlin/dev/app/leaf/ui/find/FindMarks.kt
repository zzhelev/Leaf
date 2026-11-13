// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.find

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.find.FindMatch
import dev.app.leaf.domain.models.Line
import java.util.IdentityHashMap

/**
 * Find's colours (fork-only, upstream issues 275 and 312), fixed as the compare badges' are: a style doesn't set them.
 * A match is pale amber, the current one a stronger amber with an outline, so it stands out in the line's tint too.
 */
data class FindColors(val match: Color, val current: Color, val outline: Color)

val LIGHT_FIND_COLORS = FindColors(match = Color(0xFFFFEAAE), current = Color(0xFFFFD873), outline = Color(0xFFA06A00))
val DARK_FIND_COLORS = FindColors(match = Color(0xFF3B2E10), current = Color(0xFF574516), outline = Color(0xFFF2C14E))

val MaterialTheme.findColors: FindColors
    @Composable
    @ReadOnlyComposable
    get() = if (colors.isLight) LIGHT_FIND_COLORS else DARK_FIND_COLORS

/** What find marks in one line of text, in the offsets of the text as drawn. */
data class LineFindMarks(
    val matches: List<IntRange> = emptyList(),
    /** The current match, if it is in this line. */
    val current: IntRange? = null,
    /** Select all selected the line. */
    val isSelected: Boolean = false,
) {
    val isEmpty: Boolean get() = matches.isEmpty() && !isSelected

    companion object {
        val NONE = LineFindMarks()
    }
}

/**
 * What find marks in a diff's lines, found by the [Line] that a row draws (by identity: a split diff draws a context
 * line on both sides, which [cellOf] tells apart). A local, so that upstream's composables between the list and a line
 * don't change.
 */
class DiffFindMarks(
    private val matches: IdentityHashMap<Line, Array<MutableList<IntRange>?>>,
    private val current: Triple<Line, Int, IntRange>?,
    private val isSplit: Boolean,
    private val goToLine: Line?,
    private val selectedCell: Int?,
    /** The current match waits to be scrolled into sight sideways; [onSidewaysRevealed] says it was. */
    val isSidewaysRevealPending: Boolean,
    val onSidewaysRevealed: () -> Unit,
) {
    fun cellOf(isOldSide: Boolean): Int = if (isSplit && !isOldSide) 1 else 0

    fun marksOf(line: Line, isOldSide: Boolean): LineFindMarks {
        val cell = cellOf(isOldSide)
        val ranges = matches[line]?.getOrNull(cell).orEmpty()
        val current = current?.takeIf { it.first === line && it.second == cell }?.third
        val isSelected = selectedCell == cell

        return if (ranges.isEmpty() && !isSelected) LineFindMarks.NONE else LineFindMarks(ranges, current, isSelected)
    }

    fun isGoToLine(line: Line): Boolean = line === goToLine

    companion object {
        /**
         * The marks of [matches] in a diff whose rows draw [rowLines], a list per row with a line per cell (null for a
         * header or a side without one), as `diffFindLines` gives them.
         */
        fun of(
            rowLines: List<List<Line?>>,
            matches: List<FindMatch>,
            current: FindMatch?,
            isSplit: Boolean,
            goToRow: Int?,
            selectedCell: Int?,
            isSidewaysRevealPending: Boolean = false,
            onSidewaysRevealed: () -> Unit = {},
        ): DiffFindMarks {
            val byLine = IdentityHashMap<Line, Array<MutableList<IntRange>?>>()

            for (match in matches) {
                val line = rowLines.getOrNull(match.row)?.getOrNull(match.cell) ?: continue
                val cells = byLine.getOrPut(line) { arrayOfNulls(2) }
                val ranges = cells[match.cell] ?: ArrayList<IntRange>().also { cells[match.cell] = it }

                ranges.add(match.start until match.end)
            }

            val currentMark = current?.let { match ->
                rowLines.getOrNull(match.row)?.getOrNull(match.cell)
                    ?.let { Triple(it, match.cell, match.start until match.end) }
            }
            // A split row's new side has the number that go to line takes, unless only the old side has a line
            val goToLine = goToRow?.let { row -> rowLines.getOrNull(row)?.lastOrNull { line -> line != null } }

            return DiffFindMarks(
                byLine, currentMark, isSplit, goToLine, selectedCell, isSidewaysRevealPending, onSidewaysRevealed,
            )
        }
    }
}

val LocalDiffFindMarks = compositionLocalOf<DiffFindMarks?> { null }

/** Adds [marks]' backgrounds to text that is [length] long, as drawn: Select all's, then the matches'. */
fun AnnotatedString.Builder.addFindStyles(marks: LineFindMarks, length: Int, colors: FindColors, selection: Color) {
    if (marks.isSelected && length > 0) {
        addStyle(SpanStyle(background = selection), 0, length)
    }

    for (range in marks.matches) {
        val start = range.first.coerceIn(0, length)
        val end = (range.last + 1).coerceIn(0, length)
        if (start >= end) continue

        addStyle(SpanStyle(background = if (range == marks.current) colors.current else colors.match), start, end)
    }
}

/** The background that Select all gives the lines: the text selection's. */
val selectionBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalTextSelectionColors.current.backgroundColor

/** An outline around [current] in the text that [layout] laid out, for the current match. */
fun Modifier.currentMatchOutline(current: IntRange?, layout: () -> TextLayoutResult?, color: Color): Modifier {
    if (current == null) return this

    return drawBehind {
        val textLayout = layout() ?: return@drawBehind
        drawMatchOutline(textLayout, current, color)
    }
}

private fun DrawScope.drawMatchOutline(layout: TextLayoutResult, range: IntRange, color: Color) {
    val length = layout.layoutInput.text.length
    val start = range.first.coerceIn(0, length)
    val end = (range.last + 1).coerceIn(0, length)
    if (start >= end) return

    val bounds = layout.getPathForRange(start, end).getBounds()
    val stroke = 1.5.dp.toPx()

    drawRoundRect(
        color = color,
        topLeft = Offset(bounds.left - stroke / 2, bounds.top),
        size = Size(bounds.width + stroke, bounds.height),
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(stroke),
    )
}

/** Where [range] starts and ends across the text that [layout] laid out, for scrolling it into sight sideways. */
fun horizontalBoundsOf(layout: TextLayoutResult, range: IntRange): ClosedFloatingPointRange<Float>? {
    val length = layout.layoutInput.text.length
    val start = range.first.coerceIn(0, length)
    val end = (range.last + 1).coerceIn(0, length)
    if (start >= end) return null

    val bounds = layout.getPathForRange(start, end).getBounds()

    return bounds.left..bounds.right
}

/** The row that go to line went to: a bar at its start, in the focus colour. */
@Composable
fun Modifier.goToLineMark(isMarked: Boolean): Modifier {
    if (!isMarked) return this

    val color = MaterialTheme.colors.primaryVariant

    return drawBehind { drawRect(color, size = Size(3.dp.toPx(), size.height)) }
}
