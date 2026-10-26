// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

import dev.app.leaf.domain.highlighting.SyntaxCategory.COMMENT
import dev.app.leaf.domain.highlighting.SyntaxCategory.KEYWORD
import dev.app.leaf.domain.highlighting.SyntaxCategory.STRING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HighlightedTextTest {

    private fun HighlightedText.lines() = (0 until lineCount).map { spansOf(it) }

    @Test
    fun `spans are kept per line, in order`() {
        val text = HighlightedText.Builder().apply {
            add(0, 3, KEYWORD)
            add(4, 9, STRING)
            endLine()
            endLine()
            add(2, 6, COMMENT)
            endLine()
        }.build(3)
        assertEquals(
            listOf(
                listOf(SyntaxSpan(0, 3, KEYWORD), SyntaxSpan(4, 9, STRING)),
                emptyList(),
                listOf(SyntaxSpan(2, 6, COMMENT)),
            ),
            text.lines(),
        )
        assertEquals(3, text.spanCount)
        assertNull(text.plainFrom)
    }

    @Test
    fun `touching spans of one style join, and plain ones are left out`() {
        val text = HighlightedText.Builder().apply {
            add(0, 1, STRING)
            add(1, 5, STRING)
            add(5, 6, null)
            add(6, 8, STRING)
            add(8, 9, STRING, bold = true)
            add(9, 12, null, italic = true)
            add(12, 12, KEYWORD)
            endLine()
            // Spans don't join across lines.
            add(0, 2, STRING)
            endLine()
        }.build(2)
        assertEquals(
            listOf(
                listOf(
                    SyntaxSpan(0, 5, STRING), SyntaxSpan(6, 8, STRING), SyntaxSpan(8, 9, STRING, bold = true),
                    SyntaxSpan(9, 12, null, italic = true),
                ),
                listOf(SyntaxSpan(0, 2, STRING)),
            ),
            text.lines(),
        )
    }

    @Test
    fun `lines that weren't built are plain, and so are lines past the end`() {
        val builder = HighlightedText.Builder()
        repeat(200) { line ->
            builder.add(0, line + 1, SyntaxCategory.entries[line % SyntaxCategory.entries.size])
            builder.endLine()
        }
        val text = builder.build(lineCount = 250, plainFrom = 200)
        assertEquals(250, text.lineCount)
        assertEquals(200, text.plainFrom)
        val last = SyntaxCategory.entries[199 % SyntaxCategory.entries.size]
        assertEquals(listOf(SyntaxSpan(0, 200, last)), text.spansOf(199))
        assertEquals(emptyList<SyntaxSpan>(), text.spansOf(200))
        assertEquals(emptyList<SyntaxSpan>(), text.spansOf(249))
        assertEquals(emptyList<SyntaxSpan>(), text.spansOf(250))
        assertEquals(emptyList<SyntaxSpan>(), text.spansOf(-1))
        assertThrows<IllegalArgumentException> { HighlightedText.Builder().apply { endLine(); endLine() }.build(1) }
    }

    @Test
    fun `a stopped line keeps the spans it has`() {
        val text = HighlightedText.Builder().apply {
            endLine()
            add(0, 4, KEYWORD)
        }.build(lineCount = 3, plainFrom = 1)
        assertEquals(listOf(emptyList(), listOf(SyntaxSpan(0, 4, KEYWORD)), emptyList()), text.lines())
    }

    @Test
    fun `lines are split at line feeds, without the carriage return before one`() {
        assertEquals(listOf("a", "b", "", "c\rd", ""), linesOf("a\r\nb\n\nc\rd\n").toList())
        assertEquals(listOf(""), linesOf("").toList())
        assertEquals(listOf("", ""), linesOf("\r\n").toList())
        assertEquals(listOf("one"), linesOf("one").toList())
        for (text in listOf("a\r\nb\n\nc\rd\n", "", "\r\n", "one", "\n\n\n")) {
            assertEquals(linesOf(text).count(), lineCountOf(text), text)
        }
    }
}
