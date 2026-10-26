// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.extensions.replaceTabs
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.ui.components.LocalSidewaysScroll
import dev.app.leaf.ui.components.SidewaysEndMargin
import dev.app.leaf.ui.components.SidewaysScroll
import dev.app.leaf.ui.components.UnwrappedLine
import dev.app.leaf.ui.diff.DiffText
import dev.app.leaf.ui.diff.MAX_DIFF_LINE_CHARACTERS
import dev.app.leaf.ui.diff.syntax_highlighter.SyntaxHighlighter
import org.eclipse.jgit.diff.RawText

/** The space that [DiffText] leaves before a line, which stays between the line numbers and the text while it scrolls. */
private val LineStartPadding = 16.dp

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
        DiffText(text, matchLine, insertedColor = markColor, legacyHighlighter = syntaxHighlighter)
    } else {
        UnwrappedLine(sideways, start = LineStartPadding) {
            DiffText(text, matchLine, insertedColor = markColor, legacyHighlighter = syntaxHighlighter)
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
                contentWidth = lineWidth + SidewaysEndMargin.toPx(),
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
