// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.domain.highlighting.highlightedTextOf
import dev.app.leaf.domain.highlighting.lines
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The conflict resolver's colours (fork-only, step 6 of `docs/fork/syntax-highlighting.md`). */
class ResolverSyntaxTest {
    private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

    private fun lines(start: Int, end: Int) = LineRange(start, end)

    private val merge = TextMerge(
        base = text("/* A", "   B */", "fun a() = 1", "x"),
        ours = text("/* A", "   B */", "import c", "fun a() = 1", "y"),
        theirs = text("/* A", "   B */", "fun a() = 1", "z"),
        chunks = listOf(
            Chunk.Unchanged(lines(0, 2), lines(0, 2), lines(0, 2)),
            Chunk.Change(lines(2, 2), lines(2, 3), lines(2, 2), ChangedBy.OURS),
            Chunk.Unchanged(lines(2, 3), lines(3, 4), lines(2, 3)),
            Chunk.Conflict(lines(3, 4), lines(4, 5), lines(3, 4)),
        ),
    )

    /** A text with [lines] lines, each with one span of [category] that starts at its number. */
    private fun coloured(lines: Int, category: SyntaxCategory) =
        highlightedTextOf((0 until lines).map { listOf(SyntaxSpan(it, it + 1, category)) })

    private val versions = HighlightedVersions()
        .with(merge.base, "a.kt", coloured(4, SyntaxCategory.COMMENT))
        .with(merge.ours, "a.kt", coloured(5, SyntaxCategory.KEYWORD))
        .with(merge.theirs, "a.kt", coloured(4, SyntaxCategory.STRING))

    @Test
    fun `a cell's spans are its version's, found by its text, and a filler or an unknown text has none`() {
        assertEquals(listOf(SyntaxSpan(3, 4, SyntaxCategory.KEYWORD)), versions.spansOf(merge.ours, 3))
        assertEquals(listOf(SyntaxSpan(2, 3, SyntaxCategory.STRING)), versions.spansOf(merge.theirs, 2))
        assertEquals(emptyList<SyntaxSpan>(), versions.spansOf(null, 0))
        assertEquals(emptyList<SyntaxSpan>(), versions.spansOf(text("/* A"), 0))
        // A version that nothing colours is known, with no spans
        val plain = text("x")
        assertEquals(emptyList<SyntaxSpan>(), versions.with(plain, "a.kt", null).spansOf(plain, 0))
        // Another colouring of a version takes the place of the one it had
        val again = versions.with(merge.ours, "a.kt", coloured(5, SyntaxCategory.TYPE))
        assertEquals(SyntaxCategory.TYPE, again.spansOf(merge.ours, 0).single().category)
        assertEquals(3, again.versions.size)
    }

    @Test
    fun `editing starts from the colours that the rows show`() {
        // An open conflict starts from the base's line, a part that the result shows from its lines
        val conflict = merge.editStartSyntax(3, versions)
        assertEquals("x", conflict.text)
        assertEquals(listOf(SyntaxSpan(3, 4, SyntaxCategory.COMMENT)), conflict.highlighted.spansOf(0))

        val change = merge.editStartSyntax(1, versions)
        assertEquals("import c", change.text)
        assertEquals(listOf(SyntaxSpan(2, 3, SyntaxCategory.KEYWORD)), change.highlighted.spansOf(0))
        assertEquals(listOf(merge.ours to 2), merge.editStartLines(1))

        // Lines typed: theirs
        val typed = TypedLines(text("val t = 1", "val u = 2"))
        val edited = merge.typing(3, typed)
        val withTyped = versions.with(typed.text, "a.kt", coloured(2, SyntaxCategory.NUMBER))
        assertEquals(listOf(typed.text to 0, typed.text to 1), edited.editStartLines(3))
        assertEquals(listOf(SyntaxSpan(1, 2, SyntaxCategory.NUMBER)), edited.editStartSyntax(3, withTyped).highlighted.spansOf(1))
    }

    @Test
    fun `lines typed in a part are coloured after ours' lines before it`() {
        assertEquals("", merge.textBefore(0))
        assertEquals("/* A\n   B */\n", merge.textBefore(1))
        assertEquals("/* A\n   B */\nimport c\nfun a() = 1\n", merge.textBefore(3))

        val crlf = TextMerge(text("a"), RawText("a\r\nb\r\n".toByteArray()), text("a"), listOf(Chunk.Unchanged(lines(0, 0), lines(1, 2), lines(0, 0))))
        assertEquals("a\r\n", crlf.textBefore(0))
    }

    @Test
    fun `the lines typed in a merge, with their chunks`() {
        val unchanged = TypedLines(text("u"))
        val change = TypedLines(text("c"))
        val conflict = TypedLines(text("k"))
        val edited = merge.typing(0, unchanged).typing(1, change).typing(3, conflict)

        assertEquals(listOf(0 to unchanged, 1 to change, 3 to conflict), edited.typedLines())
        assertEquals(emptyList<Pair<Int, TypedLines>>(), merge.typedLines())
        assertEquals(emptyList<Pair<Int, TypedLines>>(), merge.resolving(3, ConflictResolution.Ours).typedLines())
    }

    private val syntax = EditorSyntax(
        "val a = 1\nfun b() {}\n// c\nval d",
        highlightedTextOf(
            listOf(
                listOf(SyntaxSpan(0, 3, SyntaxCategory.KEYWORD), SyntaxSpan(8, 9, SyntaxCategory.NUMBER)),
                listOf(SyntaxSpan(0, 3, SyntaxCategory.KEYWORD), SyntaxSpan(4, 5, SyntaxCategory.FUNCTION)),
                listOf(SyntaxSpan(0, 4, SyntaxCategory.COMMENT)),
                listOf(SyntaxSpan(0, 3, SyntaxCategory.KEYWORD)),
            ),
        ),
    )

    @Test
    fun `the field's lines keep their colours while they're the same, before and after a change`() {
        val same = syntax.text.split('\n')
        assertEquals((0 until 4).map(syntax.highlighted::spansOf), syntax.spansFor(same))

        // A line inserted: the lines before and after keep theirs, the new one has none
        val inserted = listOf("val a = 1", "fun b() {}", "new line", "// c", "val d")
        val spans = syntax.spansFor(inserted)
        assertEquals(syntax.highlighted.spansOf(1), spans[1])
        assertEquals(emptyList<SyntaxSpan>(), spans[2])
        assertEquals(syntax.highlighted.spansOf(2), spans[3])
        assertEquals(syntax.highlighted.spansOf(3), spans[4])

        // A line deleted
        val deleted = syntax.spansFor(listOf("val a = 1", "// c", "val d"))
        assertEquals(listOf(syntax.highlighted.spansOf(0), syntax.highlighted.spansOf(2), syntax.highlighted.spansOf(3)), deleted)
    }

    @Test
    fun `the line typed in keeps the colours of what comes before the change`() {
        // `fun b() {}` becoming `fun bc() {}`: the keyword stays, the function's name is cut where it changed
        val spans = syntax.spansFor(listOf("val a = 1", "fun bc() {}", "// c", "val d"))[1]
        assertEquals(listOf(SyntaxSpan(0, 3, SyntaxCategory.KEYWORD), SyntaxSpan(4, 5, SyntaxCategory.FUNCTION)), spans)

        // `val` typed over: `va` keeps the keyword's colour up to where it changed
        assertEquals(listOf(SyntaxSpan(0, 2, SyntaxCategory.KEYWORD)), syntax.spansFor(listOf("vax a = 1"))[0])
        // Everything gone
        assertEquals(listOf(emptyList<SyntaxSpan>()), syntax.spansFor(listOf("")))
    }

    @Test
    fun `a text's lines can be taken out as a text of their own`() {
        val text: HighlightedText = coloured(5, SyntaxCategory.STRING)
        val part = text.lines(2, 2)

        assertEquals(2, part.lineCount)
        assertEquals(listOf(SyntaxSpan(2, 3, SyntaxCategory.STRING)), part.spansOf(0))
        assertEquals(listOf(SyntaxSpan(3, 4, SyntaxCategory.STRING)), part.spansOf(1))
        assertEquals(emptyList<SyntaxSpan>(), part.spansOf(2))
        // Past the end, lines are plain
        assertEquals(listOf(emptyList<SyntaxSpan>()), (0 until 1).map { text.lines(5, 1).spansOf(it) })

        val styled = highlightedTextOf(listOf(listOf(SyntaxSpan(0, 2, null, bold = true, italic = true)), emptyList()))
        assertEquals(listOf(SyntaxSpan(0, 2, null, bold = true, italic = true)), styled.spansOf(0))
        assertEquals(2, styled.lineCount)
        assertSame(null, styled.plainFrom)
    }
}
