// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The words that each side changed inside its lines, which the resolver marks. */
class ChangedWordsTest {
    @Test
    fun `each side's lines are marked where they differ from the base`() {
        val base = text("  method: \"GET\",", "  retries: 3, timeout: 30_000,")
        val ours = text("  method: \"GET\",", "  retries: 5, timeout: 30_000,")
        val theirs = text("  method: \"POST\",", "  retries: 3, timeout: 60_000,")

        assertEquals(listOf("  method: \"GET\",", "  retries: «5», timeout: 30_000,"), marked(base, ours))
        assertEquals(listOf("  method: \"«POST»\",", "  retries: 3, timeout: «60_000»,"), marked(base, theirs))
    }

    @Test
    fun `whole words are marked, and words alike between two changes join them when they're no longer`() {
        assertEquals(listOf("val «subtotal» = sum(«lines»)"), marked(text("val total = sum(items)"), text("val subtotal = sum(lines)")))
        // Not 3 matched to the base's last 3, which left 1.3«.0»
        assertEquals(listOf("\"version\": \"1.«3.0»\","), marked(text("\"version\": \"1.2.3\","), text("\"version\": \"1.3.0\",")))
        assertEquals(listOf("keep «x y»"), marked(text("keep a b"), text("keep x y")))

        // Longer than the changes on either side
        assertEquals(listOf("keep «x»->«y»"), marked(text("keep a->b"), text("keep x->y")))
        // Joined, a change can take in the words before it: "--" is longer than "y" alone, not than "y+ZZZZ"
        assertEquals(listOf("k «XXXX--y+ZZZZ»"), marked(text("k AAAA--b+CCCC"), text("k XXXX--y+ZZZZ")))
        // Never across lines: the next line's spaces would be marked
        assertEquals(
            listOf("k «gamma»,", "  «delta» k"),
            marked(text("k alpha,", "  beta k"), text("k gamma,", "  delta k")),
        )
    }

    @Test
    fun `lines changed whole, added or deleted get no marks, as their colour says it`() {
        // An added line next to a changed one
        assertEquals(listOf("a = «2»", "b = 3"), marked(text("a = 1"), text("a = 2", "b = 3")))
        // Every word changed, spaces aside
        assertEquals(listOf("beta  gamma"), marked(text("alpha delta"), text("beta  gamma")))
        // Added whole: both sides added the file, or the chunk has no base
        assertEquals(emptyMap<Int, List<IntRange>>(), changedWords(text(), LineRange(0, 0), text("a = 1"), LineRange(0, 1)))
        assertEquals(emptyMap<Int, List<IntRange>>(), changedWords(text("a = 1"), LineRange(0, 1), text(), LineRange(0, 0)))
        // Words deleted leave nothing to mark
        assertEquals(listOf("f(a)"), marked(text("f(a, b)"), text("f(a)")))
        // A run of spaces is one word: indentation that changed is marked
        assertEquals(listOf("«  »x = 1"), marked(text("    x = 1"), text("  x = 1")))
    }

    @Test
    fun `marks are ranges of the line as RawText gives it, tabs and carriage returns included`() {
        val base = RawText("\tval x = 1\r\n".toByteArray())
        val side = RawText("\tval x = 2\r\n".toByteArray())

        assertEquals(mapOf(0 to listOf(9 until 10)), changedWords(base, LineRange(0, 1), side, LineRange(0, 1)))

        // A line ending that changed isn't a word that changed
        val crlf = RawText("val x = 2\r\n".toByteArray())

        assertEquals(mapOf(0 to listOf(8 until 9)), changedWords(text("val x = 1"), LineRange(0, 1), crlf, LineRange(0, 1)))
    }

    @Test
    fun `a chunk with more words than the limit gets no marks`() {
        fun lines(count: Int, last: String) = text(*Array(count) { if (it == count - 1) last else "word $it" })

        // A line is four words: "word", " ", the number and the line's end, and the last one is six. Both sides count
        val under = 240 // 239 * 4 + 6 = 962 words a side, 1,924 in all
        val over = 260 // 259 * 4 + 6 = 1,042 a side, 2,084 in all

        assertTrue(marked(lines(under, "a b c"), lines(under, "a x c")).last() == "a «x» c")
        assertEquals("a x c", marked(lines(over, "a b c"), lines(over, "a x c")).last())
    }

    @Test
    fun `the cells of ours, theirs and the result get the marks of the side that their lines come from`() {
        // base: n=1 / same / k=1     ours: n=2 / same / k=1     theirs: n=1 / same / added / k=3
        val base = text("n = 1", "same", "k = 1")
        val ours = text("n = 2", "same", "k = 1")
        val theirs = text("n = 1", "same", "added", "k = 3")
        val merge = TextMerge(
            base, ours, theirs,
            listOf(
                Chunk.Change(lines(0, 1), lines(0, 1), lines(0, 1), ChangedBy.OURS),
                Chunk.Unchanged(lines(1, 2), lines(1, 2), lines(1, 2)),
                // Theirs added a line where ours has none, then changed the next one
                Chunk.Change(lines(2, 2), lines(2, 2), lines(2, 3), ChangedBy.THEIRS),
                Chunk.Conflict(lines(2, 3), lines(2, 3), lines(3, 4), ConflictResolution.Theirs),
            ),
        )
        val words = ChangedWords(merge)

        fun ColumnCell.marked() = text?.getString(line)?.let { mark(it, words.of(text, line)) } ?: "_"

        assertEquals(
            listOf(
                "n = «2»|n = «2»|n = 1",
                "same|same|same",
                "_|added|added",
                // The conflict took theirs: the result has theirs' lines, and their marks
                "k = 1|k = «3»|k = «3»",
            ),
            merge.columnRows(showBase = false).map { row ->
                listOf(row.ours, row.result, row.theirs).joinToString("|") { it.marked() }
            },
        )

        // Each side's line is found in its own side's chunks: ours deleted the first two lines, so its lines are two
        // fewer than theirs
        val deleted = TextMerge(
            text("x", "y", "b = 1", "z"),
            text("b = 1", "z"),
            text("x", "y", "b = 2", "z"),
            listOf(
                Chunk.Change(lines(0, 2), lines(0, 0), lines(0, 2), ChangedBy.OURS),
                Chunk.Change(lines(2, 3), lines(0, 1), lines(2, 3), ChangedBy.THEIRS),
                Chunk.Unchanged(lines(3, 4), lines(1, 2), lines(3, 4)),
            ),
        )

        assertEquals(listOf(4 until 5), ChangedWords(deleted).of(deleted.theirs, 2))

        // The base's lines, and lines typed, have none
        assertEquals(emptyList<IntRange>(), words.of(base, 0))
        assertEquals(emptyList<IntRange>(), words.of(TypedLines(text("n = 9")).text, 0))
        assertEquals(emptyList<IntRange>(), words.of(null, 0))
    }

    /** Each of [side]'s lines, with its marks against all of [base] between « and ». */
    private fun marked(base: RawText, side: RawText): List<String> {
        val marks = changedWords(base, LineRange(0, base.size()), side, LineRange(0, side.size()))

        return (0 until side.size()).map { line -> mark(side.getString(line), marks[line].orEmpty()) }
    }
}

private fun mark(line: String, marks: List<IntRange>): String = buildString {
    line.forEachIndexed { index, char ->
        if (marks.any { it.first == index }) append('«')
        append(char)
        if (marks.any { it.last == index }) append('»')
    }
}

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)
