// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.app.leaf.domain.DiffMatchPatch
import dev.app.leaf.domain.DiffMatchPatch.Operation
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.domain.models.MatchLine
import dev.app.leaf.theme.SyntaxColors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A line as the diff draws it: its text, cut, its word highlights, and its syntax colours (fork-only). */
class FormatDiffTest {
    private val muted = Color.Gray
    private val added = Color.Cyan
    private val removed = Color.Red
    private val colors = SyntaxColors(
        keyword = Color(0xFF000001),
        annotation = Color(0xFF000002),
        comment = Color(0xFF000003),
        string = Color(0xFF000004),
        number = Color(0xFF000005),
        type = Color(0xFF000006),
        function = Color(0xFF000007),
        tag = Color(0xFF000008),
        attribute = Color(0xFF000009),
    )

    private fun equal(text: String) = MatchLine(listOf(DiffMatchPatch.Diff(Operation.EQUAL, text)))

    private fun format(
        line: MatchLine,
        spans: List<SyntaxSpan> = emptyList(),
        hiddenCharactersText: String? = null,
    ): AnnotatedString = formatDiff(
        line = line,
        spans = spans,
        syntaxColors = colors,
        contentAddedColor = added,
        contentRemovedColor = removed,
        hiddenCharactersText = hiddenCharactersText,
        mutedColor = muted,
    )

    /** The pieces of [formatted]'s text drawn in [color]. */
    private fun coloured(formatted: AnnotatedString, color: Color) =
        formatted.spanStyles.filter { it.item.color == color }.map { formatted.text.substring(it.start, it.end) }

    @Test
    fun `each span takes its category's colour, and plain spans their weight and slant`() {
        val text = "val name = \"x\" // note"
        val formatted = format(
            equal(text),
            listOf(
                SyntaxSpan(0, 3, SyntaxCategory.KEYWORD),
                SyntaxSpan(4, 8, null, bold = true),
                SyntaxSpan(11, 14, SyntaxCategory.STRING, italic = true),
                SyntaxSpan(15, 22, SyntaxCategory.COMMENT),
            ),
        )

        assertEquals(text, formatted.text)
        assertEquals(listOf("val"), coloured(formatted, colors.keyword))
        assertEquals(listOf("\"x\""), coloured(formatted, colors.string))
        assertEquals(listOf("// note"), coloured(formatted, colors.comment))
        val bold = formatted.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("name", text.substring(bold.start, bold.end))
        assertEquals(Color.Unspecified, bold.item.color)
        val italic = formatted.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("\"x\"", text.substring(italic.start, italic.end))
    }

    @Test
    fun `every category has its own colour`() {
        val text = SyntaxCategory.entries.joinToString(" ") { it.name.lowercase() }
        var start = 0
        val spans = SyntaxCategory.entries.map { category ->
            val end = start + category.name.length
            SyntaxSpan(start, end, category).also { start = end + 1 }
        }

        val formatted = format(equal(text), spans)

        for (category in SyntaxCategory.entries) {
            assertEquals(listOf(category.name.lowercase()), coloured(formatted, colors.colorOf(category)), "$category")
        }
    }

    @Test
    fun `a tab is drawn as four spaces, and the spans after it move with it`() {
        // `\tif (a)\t{` as the diff draws it: `    if (a)    {`
        val formatted = format(
            equal("\tif (a)\t{\n"),
            listOf(SyntaxSpan(1, 3, SyntaxCategory.KEYWORD), SyntaxSpan(6, 9, SyntaxCategory.COMMENT)),
        )

        assertEquals("    if (a)    {", formatted.text)
        assertEquals(listOf("if"), coloured(formatted, colors.keyword))
        // A span over a tab covers its four spaces
        assertEquals(listOf(")    {"), coloured(formatted, colors.comment))
    }

    @Test
    fun `the line ending is left out, and a span that runs past the line stops at its end`() {
        val formatted = format(equal("x = 1\r\n"), listOf(SyntaxSpan(4, 20, SyntaxCategory.NUMBER)))

        assertEquals("x = 1", formatted.text)
        assertEquals(listOf("1"), coloured(formatted, colors.number))
    }

    @Test
    fun `the word highlights and the colours are on the same characters`() {
        val line = MatchLine(
            listOf(
                DiffMatchPatch.Diff(Operation.EQUAL, "val a = "),
                DiffMatchPatch.Diff(Operation.INSERT, "\"new\""),
                DiffMatchPatch.Diff(Operation.EQUAL, "\n"),
            ),
        )

        val formatted = format(line, listOf(SyntaxSpan(0, 3, SyntaxCategory.KEYWORD), SyntaxSpan(8, 13, SyntaxCategory.STRING)))

        assertEquals("val a = \"new\"", formatted.text)
        assertEquals(listOf("\"new\""), coloured(formatted, colors.string))
        val inserted = formatted.spanStyles.single { it.item.background == added }
        assertEquals("\"new\"", formatted.text.substring(inserted.start, inserted.end))
        assertEquals(listOf("val"), coloured(formatted, colors.keyword))
    }

    @Test
    fun `a piece cut between the carriage return and the line feed keeps the spans in place`() {
        // The word diff can cut a CRLF in two, which leaves the `\r` in the text drawn (upstream's quirk)
        val line = MatchLine(
            listOf(
                DiffMatchPatch.Diff(Operation.EQUAL, "\tfun"),
                DiffMatchPatch.Diff(Operation.DELETE, " old()\r"),
                DiffMatchPatch.Diff(Operation.EQUAL, "\n"),
            ),
        )

        val formatted = format(line, listOf(SyntaxSpan(1, 4, SyntaxCategory.KEYWORD), SyntaxSpan(5, 8, SyntaxCategory.FUNCTION)))

        assertEquals("    fun old()\r", formatted.text)
        assertEquals(listOf("fun"), coloured(formatted, colors.keyword))
        assertEquals(listOf("old"), coloured(formatted, colors.function))
    }

    @Test
    fun `a long line stops after the limit, with how much is left out`() {
        val line = "val x = 1 ".repeat(5_000) // 50,000 characters

        val formatted = format(equal(line), hiddenCharactersText = "40,000 more characters")

        assertEquals(line.take(MAX_DIFF_LINE_CHARACTERS) + " … 40,000 more characters", formatted.text)
        val marker = formatted.spanStyles.single { it.item.color == muted }
        assertEquals(MAX_DIFF_LINE_CHARACTERS, marker.start)
    }

    @Test
    fun `only the start of a long line is coloured, and a span across the limit is cut there`() {
        val line = "val x = 1 ".repeat(500) // 5,000 characters
        val spans = (0 until 500).map { SyntaxSpan(it * 10, it * 10 + 3, SyntaxCategory.KEYWORD) } +
            SyntaxSpan(995, 1_005, SyntaxCategory.STRING)

        val formatted = format(equal(line), spans.sortedBy { it.start })
        val keywords = formatted.spanStyles.filter { it.item.color == colors.keyword }

        assertEquals(5_000, formatted.text.length)
        assertEquals(100, keywords.size)
        assertTrue(keywords.all { it.end <= MAX_HIGHLIGHTED_LINE_CHARACTERS }, "$keywords")
        val string = formatted.spanStyles.single { it.item.color == colors.string }
        assertEquals(995 until MAX_HIGHLIGHTED_LINE_CHARACTERS, string.start until string.end)
    }

    @Test
    fun `the offsets of a line in pieces, as drawn`() {
        assertArrayEquals(intArrayOf(0), drawnOffsets(emptyList()))
        assertArrayEquals(intArrayOf(0, 1, 2), drawnOffsets(listOf("ab")))
        assertArrayEquals(intArrayOf(0, 4, 5, 6, 6), drawnOffsets(listOf("\ta", "b\n")))
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 2), drawnOffsets(listOf("ab\r\n")))
        // A `\r` that a piece ends with is drawn; the `\n` in the next piece isn't
        assertArrayEquals(intArrayOf(0, 1, 2, 2), drawnOffsets(listOf("a\r", "\n")))
    }

    @Test
    fun `spans fit a word diff cut from the same line, and nothing else`() {
        assertTrue(spansFit("val a\n", null))
        val pieces = MatchLine(listOf(DiffMatchPatch.Diff(Operation.EQUAL, "val "), DiffMatchPatch.Diff(Operation.INSERT, "a\n")))
        assertTrue(spansFit("val a\n", pieces))
        assertTrue(spansFit("val a\r\n", MatchLine(listOf(DiffMatchPatch.Diff(Operation.EQUAL, "val a\r\n")))))
        assertFalse(spansFit("val b\n", pieces))
    }
}
