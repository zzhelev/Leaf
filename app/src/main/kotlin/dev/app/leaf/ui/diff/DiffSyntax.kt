// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import dev.app.leaf.domain.extensions.removeLineDelimiters
import dev.app.leaf.domain.extensions.replaceTabs
import dev.app.leaf.domain.highlighting.DiffHighlights
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.domain.models.MatchLine
import dev.app.leaf.theme.SyntaxColors

/**
 * The colours of the versions of the diff that the lines below belong to (fork-only,
 * `docs/fork/syntax-highlighting.md`), null while neither is coloured. A local, so that upstream's composables between
 * the pane and a line don't change.
 */
val LocalDiffHighlights = compositionLocalOf<DiffHighlights?> { null }

/**
 * Where each character of a line, cut into [pieces] as the word diff cuts it, goes in the text that the diff draws: a
 * tab takes four characters there, and a piece's line ending none. One entry more, for the line's end.
 */
internal fun drawnOffsets(pieces: List<String>): IntArray {
    val offsets = IntArray(pieces.sumOf { it.length } + 1)
    var source = 0
    var drawn = 0

    for (piece in pieces) {
        val kept = piece.removeLineDelimiters().length

        for (index in piece.indices) {
            offsets[source++] = drawn
            if (index < kept) drawn += if (piece[index] == '\t') 4 else 1
        }
    }

    offsets[source] = drawn
    return offsets
}

/**
 * Whether [text]'s spans fit [matchLine], the word diff's pieces that the diff draws in its place: they're cut from
 * the same line, unless the word diff paired the line with another, which upstream's code allows for.
 */
internal fun spansFit(text: String, matchLine: MatchLine?): Boolean =
    matchLine == null || matchLine.diffs.joinToString("") { it.text }.removeLineDelimiters() == text.removeLineDelimiters()

/**
 * [text], a line of a file, as blame draws it: tabs as four spaces, without its line ending, coloured with [spans] up to
 * [MAX_HIGHLIGHTED_LINE_CHARACTERS] (step 6).
 */
fun colouredLine(text: String, spans: List<SyntaxSpan>, colors: SyntaxColors): AnnotatedString {
    val shown = text.replaceTabs().removeLineDelimiters()

    return buildAnnotatedString {
        append(shown)
        addSyntaxStyles(spans, listOf(text), minOf(shown.length, MAX_HIGHLIGHTED_LINE_CHARACTERS), colors)
    }
}

/**
 * Colours the text that the diff draws for a line cut into [pieces] with [spans], the offsets of the line's own text,
 * up to [limit] characters as drawn.
 */
internal fun AnnotatedString.Builder.addSyntaxStyles(
    spans: List<SyntaxSpan>,
    pieces: List<String>,
    limit: Int,
    colors: SyntaxColors,
) {
    if (spans.isEmpty()) return
    val offsets = drawnOffsets(pieces)
    val last = offsets.lastIndex

    for (span in spans) {
        val start = offsets[span.start.coerceIn(0, last)]
        val end = minOf(offsets[span.end.coerceIn(0, last)], limit)
        if (start >= end) continue

        val style = SpanStyle(
            color = span.category?.let(colors::colorOf) ?: Color.Unspecified,
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
        )
        addStyle(style, start, end)
    }
}
