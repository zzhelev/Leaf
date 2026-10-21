// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.domain.extensions.removeLineDelimiters
import dev.app.leaf.theme.onBackgroundSecondary

/** Whether the diff's lines mark their spaces, tabs and line endings. False outside the diff views, the resolver's. */
val LocalShowWhitespace = compositionLocalOf { false }

/** The columns that a tab takes in a diff: `DiffText` shows each tab as this many spaces. */
internal const val TAB_COLUMNS = 4

enum class WhitespaceKind { SPACE, TAB }

/**
 * A space, or a tab, in the text that [DiffText] shows: its first character, and how many characters it takes there
 * (a tab is [TAB_COLUMNS] spaces).
 */
data class WhitespaceMark(val start: Int, val length: Int, val kind: WhitespaceKind) {
    val end get() = start + length
}

/**
 * Where a line has whitespace to mark, in the characters of what the diff shows ([formatDiff]: tabs as spaces, without
 * its line ending), up to [MAX_DIFF_LINE_CHARACTERS].
 *
 * @param trailingStart where the whitespace at the end of the line starts, or -1 for none: the first of the marks that
 * reach the line's end. A line that is cut at [MAX_DIFF_LINE_CHARACTERS] has none.
 * @param endsWithCarriageReturn the line ends with `\r\n`, which the diff doesn't show.
 */
data class WhitespaceMarks(
    val marks: List<WhitespaceMark>,
    val trailingStart: Int,
    val endsWithCarriageReturn: Boolean,
) {
    fun isTrailing(mark: WhitespaceMark) = trailingStart >= 0 && mark.start >= trailingStart

    companion object {
        val NONE = WhitespaceMarks(emptyList(), -1, false)
    }
}

/**
 * The marks of a line that the diff shows as [pieces], one per run that its word comparison split it into: each is
 * shown without its line ending, with its tabs as spaces, and they follow each other.
 */
fun whitespaceMarksOf(pieces: List<String>): WhitespaceMarks {
    val marks = ArrayList<WhitespaceMark>()
    var length = 0
    var last = ' '
    // The word diff may cut between the `\r` and the `\n`, which then are pieces of their own
    val carriageReturn = pieces.joinToString("").endsWith("\r\n")

    for (piece in pieces) {
        for (character in piece.removeLineDelimiters()) {
            last = character

            when (character) {
                ' ' -> {
                    if (length < MAX_DIFF_LINE_CHARACTERS) marks.add(WhitespaceMark(length, 1, WhitespaceKind.SPACE))
                    length++
                }

                '\t' -> {
                    if (length < MAX_DIFF_LINE_CHARACTERS) marks.add(WhitespaceMark(length, TAB_COLUMNS, WhitespaceKind.TAB))
                    length += TAB_COLUMNS
                }

                else -> length++
            }
        }
    }

    // A CR that the word diff cut off the line's end stays in the text that is shown, after the whitespace
    val contentEnd = if (last == '\r') length - 1 else length
    var trailingStart = -1

    if (contentEnd <= MAX_DIFF_LINE_CHARACTERS) {
        var end = contentEnd

        for (mark in marks.asReversed()) {
            if (mark.end != end) break

            end = mark.start
            trailingStart = end
        }
    }

    return WhitespaceMarks(marks, trailingStart, carriageReturn)
}

/**
 * What [DiffText] needs to draw the marks of one line over its text: nothing of the text changes, so what is selected
 * and copied is what the line says. Null while [LocalShowWhitespace] is off.
 */
internal class WhitespaceOverlay(
    val modifier: Modifier,
    val onTextLayout: (TextLayoutResult) -> Unit,
)

@Composable
internal fun rememberWhitespaceOverlay(pieces: List<String>): WhitespaceOverlay? {
    if (!LocalShowWhitespace.current) {
        return null
    }

    val marks = remember(pieces) { whitespaceMarksOf(pieces) }
    val measurer = rememberTextMeasurer()
    val colors = WhitespaceColors(
        mark = MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.55f),
        trailing = MaterialTheme.colors.error.copy(alpha = 0.35f),
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    return remember(marks, measurer, colors) {
        WhitespaceOverlay(
            modifier = Modifier.drawWithContent {
                drawContent()
                layout?.let { drawWhitespace(marks, it, measurer, colors) }
            },
            // Read by the draw above only, so a new layout draws the marks again without composing the line again
            onTextLayout = { layout = it },
        )
    }
}

internal data class WhitespaceColors(val mark: Color, val trailing: Color)

/** Draws [marks] over [layout], the text that was laid out: a dot for a space, an arrow for a tab, `CR` after the end. */
internal fun DrawScope.drawWhitespace(
    marks: WhitespaceMarks,
    layout: TextLayoutResult,
    measurer: TextMeasurer,
    colors: WhitespaceColors,
) {
    val textLength = layout.layoutInput.text.length
    val dot = 1.3.dp.toPx()
    val stroke = Stroke(width = 1.dp.toPx())

    for (mark in marks.marks) {
        // A cut line has the note about the rest after it, and a line that was laid out again may be shorter
        if (mark.end > textLength) break

        val first = layout.getBoundingBox(mark.start)

        if (marks.isTrailing(mark)) {
            for (column in mark.start until mark.end) {
                val cell = layout.getBoundingBox(column)
                drawRect(colors.trailing, cell.topLeft, cell.size)
            }
        }

        when (mark.kind) {
            WhitespaceKind.SPACE -> drawCircle(colors.mark, dot, first.center)
            WhitespaceKind.TAB -> {
                val last = layout.getBoundingBox(mark.end - 1)
                // A tab that the line wrapped in the middle of: an arrow in its first column
                val sameRow = last.top == first.top
                val left = first.left + 3.dp.toPx()
                val right = (if (sameRow) last.right else first.right) - 3.dp.toPx()
                val y = first.center.y
                val head = 3.dp.toPx()

                if (right > left) {
                    drawLine(colors.mark, Offset(left, y), Offset(right, y), stroke.width)
                    drawLine(colors.mark, Offset(right, y), Offset(right - head, y - head), stroke.width)
                    drawLine(colors.mark, Offset(right, y), Offset(right - head, y + head), stroke.width)
                }
            }
        }
    }

    if (marks.endsWithCarriageReturn) {
        drawCarriageReturn(layout, measurer, colors)
    }
}

/** `CR` in a small outline, after the last character, where git has the `\r` that the line ends with. */
private fun DrawScope.drawCarriageReturn(layout: TextLayoutResult, measurer: TextMeasurer, colors: WhitespaceColors) {
    val style = TextStyle(color = colors.mark, fontSize = 8.sp)
    val label = measurer.measure("CR", style)
    val lastLine = layout.lineCount - 1
    val gap = 6.dp.toPx()
    val padding = 2.dp.toPx()
    val height = label.size.height + padding
    val left = layout.getLineRight(lastLine) + gap
    val top = layout.getLineTop(lastLine) + (layout.getLineBottom(lastLine) - layout.getLineTop(lastLine) - height) / 2

    drawRoundRect(
        color = colors.mark,
        topLeft = Offset(left, top),
        size = Size(label.size.width + 2 * padding, height),
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(width = 0.5.dp.toPx()),
    )
    drawText(measurer, "CR", Offset(left + padding, top + padding / 2), style)
}
