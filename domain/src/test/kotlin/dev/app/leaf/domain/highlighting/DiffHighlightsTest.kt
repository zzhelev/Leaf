// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

import dev.app.leaf.domain.extensions.lineAt
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.SplitHunk
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DiffHighlightsTest {

    /** A text of [lines] lines, each with one span of [category] whose start is the line's number. */
    private fun highlighted(lines: Int, category: SyntaxCategory): HighlightedText {
        val builder = HighlightedText.Builder()
        repeat(lines) { line ->
            builder.add(line, line + 1, category)
            builder.endLine()
        }
        return builder.build(lines)
    }

    private val old = highlighted(10, SyntaxCategory.COMMENT)
    private val new = highlighted(10, SyntaxCategory.STRING)

    private fun DiffHighlights.spanOf(line: Line, onOldSide: Boolean = false) = spansOf(line, onOldSide).single()

    @Test
    fun `a removed line takes the old version's colours, an added line the new one's, each by its own number`() {
        val highlights = DiffHighlights(old, new)

        assertEquals(SyntaxSpan(3, 4, SyntaxCategory.COMMENT), highlights.spanOf(Line("x", 3, 5, LineType.REMOVED)))
        assertEquals(SyntaxSpan(5, 6, SyntaxCategory.STRING), highlights.spanOf(Line("x", 3, 5, LineType.ADDED)))
        // The split diff's sides change nothing for them
        assertEquals(SyntaxSpan(3, 4, SyntaxCategory.COMMENT), highlights.spanOf(Line("x", 3, 5, LineType.REMOVED), true))
        assertEquals(SyntaxSpan(5, 6, SyntaxCategory.STRING), highlights.spanOf(Line("x", 3, 5, LineType.ADDED), false))
    }

    @Test
    fun `a context line takes the new version's colours, and on the old side the old one's`() {
        val highlights = DiffHighlights(old, new)
        val context = Line("x", 2, 7, LineType.CONTEXT)

        assertEquals(SyntaxSpan(7, 8, SyntaxCategory.STRING), highlights.spanOf(context))
        assertEquals(SyntaxSpan(2, 3, SyntaxCategory.COMMENT), highlights.spanOf(context, onOldSide = true))
    }

    @Test
    fun `a context line of a diff that ignored whitespace shows the new text, so the new colours, on either side`() {
        val highlights = DiffHighlights(old, new, contextLinesAreNew = true)
        val context = Line("x", 2, 7, LineType.CONTEXT)

        assertEquals(SyntaxSpan(7, 8, SyntaxCategory.STRING), highlights.spanOf(context, onOldSide = true))
        assertEquals(SyntaxSpan(3, 4, SyntaxCategory.COMMENT), highlights.spanOf(Line("x", 3, 5, LineType.REMOVED)))
    }

    @Test
    fun `a version that isn't coloured, or a line past its end, has no spans`() {
        val highlights = DiffHighlights(old = null, new = new)

        assertEquals(emptyList<SyntaxSpan>(), highlights.spansOf(Line("x", 1, 1, LineType.REMOVED)))
        assertEquals(emptyList<SyntaxSpan>(), highlights.spansOf(Line("x", 1, 1, LineType.CONTEXT), onOldSide = true))
        assertEquals(emptyList<SyntaxSpan>(), highlights.spansOf(Line("x", 1, 40, LineType.ADDED)))
    }

    private val entry = object : DiffEntry() {
        init {
            changeType = ChangeType.RENAME
            oldPath = "src/Old.txt"
            newPath = "src/New.kt"
        }
    }

    private fun hunk(oldText: RawText, newText: RawText, ignoresWhitespace: Boolean = false) =
        Hunk("@@ -1 +1 @@", listOf(Line("a\n", 0, 0, LineType.CONTEXT)), oldText, newText, ignoresWhitespace)

    @Test
    fun `a diff's versions are found by their texts, the instances, in unified and split diffs`() {
        val oldText = RawText("a\n".toByteArray())
        val newText = RawText("a\nb\n".toByteArray())
        val versions = HighlightedVersions(
            listOf(
                HighlightedVersions.Version(oldText, "src/Old.txt", old),
                HighlightedVersions.Version(newText, "src/New.kt", new),
            ),
        )
        val unified = DiffResult.Text(entry, listOf(hunk(oldText, newText, ignoresWhitespace = true)))
        val split = DiffResult.TextSplit(entry, listOf(SplitHunk(unified.hunks.single(), emptyList())))

        for (diff in listOf(unified, split)) {
            val highlights = versions.forDiff(diff)!!
            assertSame(old, highlights.old)
            assertSame(new, highlights.new)
            // The hunk's flag is the highlights'
            assertEquals(SyntaxCategory.STRING, highlights.spansOf(Line("x", 0, 0, LineType.CONTEXT), true).single().category)
        }

        // A diff loaded again has other instances, with the same bytes: not these versions'
        val again = DiffResult.Text(entry, listOf(hunk(RawText("a\n".toByteArray()), RawText("a\nb\n".toByteArray()))))
        assertNull(versions.forDiff(again))
        assertEquals(listOf(oldText to "src/Old.txt", newText to "src/New.kt"), unified.versions())
    }

    @Test
    fun `a diff without lines, or with neither version coloured, has no highlights`() {
        val text = RawText("a\n".toByteArray())
        val versions = HighlightedVersions(listOf(HighlightedVersions.Version(text, "a.kt", null)))

        assertNull(versions.forDiff(DiffResult.Text(entry, emptyList())))
        assertNull(versions.forDiff(DiffResult.Text(entry, listOf(hunk(text, text)))))
        assertEquals(emptyList<Pair<RawText, String>>(), DiffResult.Text(entry, emptyList()).versions())

        val coloured = versions.withHighlighted(text, new)
        assertSame(new, coloured.forDiff(DiffResult.Text(entry, listOf(hunk(text, text))))!!.new)
        // Only that instance
        assertNull(versions.withHighlighted(RawText("a\n".toByteArray()), new).versions.single().highlighted)
    }

    @Test
    fun `a version is decoded a line at a time, as the diff shows its lines`() {
        // A line in ISO-8859-1 next to one in UTF-8: decoded whole, both fall back to one charset
        val bytes = "caf".toByteArray() + byteArrayOf(0xE9.toByte()) + "\n".toByteArray() + "naïve\r\nend".toByteArray()
        val text = RawText(bytes)

        val decoded = text.decodedText()

        assertEquals((0 until text.size()).joinToString("") { text.lineAt(it) }, decoded)
        assertEquals(listOf("café", "naïve", "end"), linesOf(decoded).toList())
        assertNotEquals(decoded, text.getString(0, text.size(), false))
        assertEquals("", RawText.EMPTY_TEXT.decodedText())
    }
}
