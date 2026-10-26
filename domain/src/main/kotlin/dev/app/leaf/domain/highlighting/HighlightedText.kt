// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

/**
 * A piece of a line drawn in one syntax style, from [start] to [end] (exclusive), in the line's UTF-16 offsets. A
 * [category] of null is plain text drawn [bold] or [italic].
 */
data class SyntaxSpan(
    val start: Int,
    val end: Int,
    val category: SyntaxCategory?,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * A text's colours, line by line (`docs/fork/syntax-highlighting.md`, 4.3). Lines are 0-based, as [linesOf] splits
 * them, and plain pieces have no span. Results are kept for many files, so the spans are three ints each.
 */
class HighlightedText private constructor(
    /** How many lines the text has, those left plain included. */
    val lineCount: Int,
    /** Where each line's spans start in [spans], in spans, and one past the last line's. */
    private val lineStarts: IntArray,
    /** Each span's start, end and style ([styleOf]), one after the other. */
    private val spans: IntArray,
    /** The first line left plain because colouring took longer than its time limit, or null when none was. */
    val plainFrom: Int?,
) {
    /** How many spans the text has: what keeping it costs. */
    val spanCount: Int get() = spans.size / INTS_PER_SPAN

    /** The spans of [line], in order, or none for a line past the text's end. */
    fun spansOf(line: Int): List<SyntaxSpan> {
        if (line < 0 || line >= lineCount) return emptyList()
        val from = lineStarts[line]
        val to = lineStarts[line + 1]
        return List(to - from) { index ->
            val at = (from + index) * INTS_PER_SPAN
            val style = spans[at + 2]
            SyntaxSpan(
                start = spans[at],
                end = spans[at + 1],
                category = (style and CATEGORY_MASK).takeIf { it != 0 }?.let { SyntaxCategory.entries[it - 1] },
                bold = style and BOLD != 0,
                italic = style and ITALIC != 0,
            )
        }
    }

    /** Builds a [HighlightedText] a line at a time, joining touching spans of one style and leaving out plain ones. */
    class Builder {
        private var spans = IntArray(INTS_PER_SPAN * 64)
        private var size = 0
        private var lineStarts = IntArray(64)
        private var lines = 0

        /** Adds a span to the line being built. Spans come in order, and don't overlap. */
        fun add(start: Int, end: Int, category: SyntaxCategory?, bold: Boolean = false, italic: Boolean = false) {
            if (end <= start) return
            val style = styleOf(category, bold, italic)
            if (style == 0) return
            val lineStart = lineStarts[lines]
            if (size > lineStart * INTS_PER_SPAN && spans[size - 1] == style && spans[size - 2] == start) {
                spans[size - 2] = end
                return
            }
            if (size + INTS_PER_SPAN > spans.size) spans = spans.copyOf(spans.size * 2)
            spans[size++] = start
            spans[size++] = end
            spans[size++] = style
        }

        /** Ends the line being built: the next span is the next line's. */
        fun endLine() {
            lines++
            if (lines + 1 >= lineStarts.size) lineStarts = lineStarts.copyOf(lineStarts.size * 2)
            lineStarts[lines] = size / INTS_PER_SPAN
        }

        /**
         * The text, with [lineCount] lines: lines that weren't ended are plain. [plainFrom] is the first line that the
         * time limit left plain.
         */
        fun build(lineCount: Int, plainFrom: Int? = null): HighlightedText {
            require(lineCount >= lines) { "$lines lines were built, more than the text's $lineCount" }
            val starts = lineStarts.copyOf(lineCount + 1)
            starts.fill(size / INTS_PER_SPAN, lines + 1, lineCount + 1)
            return HighlightedText(lineCount, starts, spans.copyOf(size), plainFrom)
        }
    }

    private companion object {
        const val INTS_PER_SPAN = 3
        const val CATEGORY_MASK = 0xF
        const val BOLD = 0x10
        const val ITALIC = 0x20

        /** A span's style as an int: the category's ordinal plus one (0 for none), and a bit for bold and italic. */
        fun styleOf(category: SyntaxCategory?, bold: Boolean, italic: Boolean): Int =
            (category?.ordinal?.plus(1) ?: 0) or (if (bold) BOLD else 0) or (if (italic) ITALIC else 0)
    }
}

/** Lines [from] to [from] + [count] of this text, as a text of their own: its line 0 is line [from] (fork-only, step 6). */
fun HighlightedText.lines(from: Int, count: Int): HighlightedText = highlightedTextOf((from until from + count).map(::spansOf))

/** A text whose lines have [lines]' spans, each line's in order. */
fun highlightedTextOf(lines: List<List<SyntaxSpan>>): HighlightedText {
    val builder = HighlightedText.Builder()

    for (spans in lines) {
        for (span in spans) {
            builder.add(span.start, span.end, span.category, span.bold, span.italic)
        }
        builder.endLine()
    }

    return builder.build(lines.size)
}

/**
 * The lines of [text], as [HighlightedText] counts them: split at `\n`, each without the `\r` before it. A text that
 * ends with a line ending has an empty last line, as an editor shows it.
 */
fun linesOf(text: String): Sequence<String> = sequence {
    var start = 0
    while (true) {
        val end = text.indexOf('\n', start)
        if (end < 0) {
            yield(text.substring(start))
            return@sequence
        }
        yield(text.substring(start, if (end > start && text[end - 1] == '\r') end - 1 else end))
        start = end + 1
    }
}

/** How many lines [text] has, as [linesOf] gives them. */
fun lineCountOf(text: String): Int = text.count { it == '\n' } + 1
