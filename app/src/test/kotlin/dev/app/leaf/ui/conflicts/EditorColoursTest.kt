// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.app.leaf.domain.conflicts.EditorSyntax
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.domain.highlighting.highlightedTextOf
import dev.app.leaf.theme.SyntaxColors
import dev.app.leaf.ui.diff.MAX_HIGHLIGHTED_LINE_CHARACTERS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The colours of the part being edited (fork-only, step 6 of `docs/fork/syntax-highlighting.md`). */
class EditorColoursTest {
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

    private val text = "\tval x = 1\n// a comment\n@Inject fun load()"
    private val syntax = EditorSyntax(
        text,
        highlightedTextOf(
            listOf(
                listOf(SyntaxSpan(1, 4, SyntaxCategory.KEYWORD), SyntaxSpan(9, 10, SyntaxCategory.NUMBER)),
                listOf(SyntaxSpan(0, 12, SyntaxCategory.COMMENT)),
                listOf(SyntaxSpan(0, 7, SyntaxCategory.ANNOTATION), SyntaxSpan(8, 11, SyntaxCategory.KEYWORD), SyntaxSpan(12, 16, SyntaxCategory.FUNCTION, bold = true)),
            ),
        ),
    )

    private fun styles(transformed: AnnotatedString) =
        transformed.spanStyles.map { Triple(it.start, it.end, it.item.color) }.sortedBy { it.first }

    @Test
    fun `the field's lines take their colours, where tabs show as spaces`() {
        val transformed = EditorColours(syntax, colors).filter(AnnotatedString(text))

        assertEquals("    val x = 1\n// a comment\n@Inject fun load()", transformed.text.text)
        assertEquals(
            listOf(
                Triple(4, 7, colors.keyword),
                Triple(12, 13, colors.number),
                Triple(14, 26, colors.comment),
                Triple(27, 34, colors.annotation),
                Triple(35, 38, colors.keyword),
                Triple(39, 43, colors.function),
            ),
            styles(transformed.text),
        )

        // Offsets are the tabs' mapping
        assertEquals(4, transformed.offsetMapping.originalToTransformed(1))
        assertEquals(1, transformed.offsetMapping.transformedToOriginal(4))
    }

    @Test
    fun `while the colours are of an earlier text, the lines that didn't change keep theirs`() {
        // A line inserted after the first, and the comment changed: the first line and the last keep theirs
        val typed = "\tval x = 1\nnew\n// a remark\n@Inject fun load()"
        val transformed = EditorColours(syntax, colors).filter(AnnotatedString(typed)).text

        val coloured = styles(transformed).map { (start, end, color) -> transformed.text.substring(start, end) to color }
        assertEquals(
            listOf(
                "val" to colors.keyword, "1" to colors.number, "@Inject" to colors.annotation, "fun" to colors.keyword,
                "load" to colors.function,
            ),
            coloured,
        )
    }

    @Test
    fun `no colours yet, a part too long to colour on every change, and a line's end past the limit stay plain`() {
        assertEquals(emptyList<AnnotatedString.Range<*>>(), EditorColours(null, colors).filter(AnnotatedString(text)).text.spanStyles)

        val long = "val x = 1\n".repeat(MAX_COLOURED_EDIT_CHARACTERS / 10 + 1)
        val longSyntax = EditorSyntax(long, highlightedTextOf(long.split('\n').map { listOf(SyntaxSpan(0, 3, SyntaxCategory.KEYWORD)) }))
        assertEquals(emptyList<AnnotatedString.Range<*>>(), EditorColours(longSyntax, colors).filter(AnnotatedString(long)).text.spanStyles)

        val wide = "x".repeat(MAX_HIGHLIGHTED_LINE_CHARACTERS + 10)
        val wideSyntax = EditorSyntax(wide, highlightedTextOf(listOf(listOf(SyntaxSpan(MAX_HIGHLIGHTED_LINE_CHARACTERS - 5, wide.length, SyntaxCategory.STRING)))))
        val string = EditorColours(wideSyntax, colors).filter(AnnotatedString(wide)).text.spanStyles.single()
        assertEquals(MAX_HIGHLIGHTED_LINE_CHARACTERS - 5 until MAX_HIGHLIGHTED_LINE_CHARACTERS, string.start until string.end)
    }
}
