// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.find

import dev.app.leaf.domain.DiffMatchPatch
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.MatchLine
import dev.app.leaf.domain.models.SplitHunk
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val empty = RawText(ByteArray(0))

private fun hunk(vararg lines: Line) = Hunk("@@", lines.toList(), empty, empty)

// Lines are 0-based in the model, and the diff shows them from 1
private val context = Line("    val a = 1\n", 9, 11, LineType.CONTEXT)
private val removed = Line("\tval b = 2\r\n", 10, 12, LineType.REMOVED)
private val added = Line("\tval b = 3\n", 10, 12, LineType.ADDED)
private val farContext = Line("end", 40, 42, LineType.CONTEXT)

/** The rows that find searches and go to line counts, laid out as the diff pane and blame lay out their lists. */
class FindRowsTest {
    @Test
    fun `a unified diff has a header row per hunk, then its lines, with the numbers that each shows`() {
        val rows = unifiedFindRows(listOf(hunk(context, removed, added), hunk(farContext)), maxCharacters = 100)

        assertEquals(
            listOf(
                FindRow(listOf(null)),
                FindRow(listOf("    val a = 1"), oldLine = 10, newLine = 12),
                FindRow(listOf("    val b = 2"), oldLine = 11, newLine = null),
                FindRow(listOf("    val b = 3"), oldLine = null, newLine = 13),
                FindRow(listOf(null)),
                FindRow(listOf("end"), oldLine = 41, newLine = 43),
            ),
            rows,
        )
    }

    @Test
    fun `a split diff's rows have the old side, then the new one`() {
        val split = SplitHunk(hunk(context, removed, added), listOf(context to context, removed to added, null to added))

        assertEquals(
            listOf(
                FindRow(listOf(null, null)),
                FindRow(listOf("    val a = 1", "    val a = 1"), oldLine = 10, newLine = 12),
                FindRow(listOf("    val b = 2", "    val b = 3"), oldLine = 11, newLine = 13),
                FindRow(listOf(null, "    val b = 3"), oldLine = null, newLine = 13),
            ),
            splitFindRows(listOf(split), maxCharacters = 100),
        )
    }

    @Test
    fun `a line's text is the word diff's pieces as drawn, cut where the pane stops drawing`() {
        val worded = Line(
            "\tkeep\n", 0, 0, LineType.ADDED,
            textDiffed = MatchLine(
                listOf(
                    DiffMatchPatch.Diff(DiffMatchPatch.Operation.EQUAL, "\tkeep"),
                    DiffMatchPatch.Diff(DiffMatchPatch.Operation.INSERT, " more\n"),
                ),
            ),
        )

        assertEquals("    keep more", worded.shownText(100))
        assertEquals("    ke", worded.shownText(6))
        assertEquals("abc", Line("abc\r\n", 0, 0, LineType.CONTEXT).shownText(3))
    }

    @Test
    fun `blame's rows are the file's lines, numbered from 1`() {
        assertEquals(
            listOf(FindRow(listOf("    a"), newLine = 1), FindRow(listOf("b"), newLine = 2)),
            blameFindRows(listOf("\ta\n", "b")),
        )
    }

    @Test
    fun `go to line takes the new version's numbers`() {
        val rows = unifiedFindRows(listOf(hunk(context, removed, added), hunk(farContext)), maxCharacters = 100)

        assertEquals(LineSide.NEW, lineSideOf(rows))
        assertEquals(LineTarget.Row(1), targetOfLine(rows, 12, lineCount = 50))
        assertEquals(LineTarget.Row(3), targetOfLine(rows, 13, lineCount = 50))
        assertEquals(LineTarget.Row(5), targetOfLine(rows, 43, lineCount = 50))
        // Old line 11 was removed: the new version has no row for it
        assertEquals(LineTarget.NotShown, targetOfLine(rows, 11, lineCount = 50))
        assertEquals(LineTarget.NotShown, targetOfLine(rows, 20, lineCount = 50))
    }

    @Test
    fun `a line past either end is the first or the last`() {
        val rows = blameFindRows(listOf("a", "b", "c"))

        assertEquals(LineTarget.Row(2), targetOfLine(rows, 99, lineCount = 3))
        assertEquals(LineTarget.Row(0), targetOfLine(rows, 0, lineCount = 3))
        assertEquals(LineTarget.Row(0), targetOfLine(rows, -4, lineCount = 3))
    }

    @Test
    fun `a deleted file goes by its old numbers, a diff without numbers nowhere`() {
        val gone = unifiedFindRows(listOf(hunk(Line("x", 0, 0, LineType.REMOVED), Line("y", 1, 0, LineType.REMOVED))), 100)

        assertEquals(LineSide.OLD, lineSideOf(gone))
        assertEquals(LineTarget.Row(2), targetOfLine(gone, 2, lineCount = 2))
        assertEquals(LineTarget.None, targetOfLine(emptyList(), 1, lineCount = 0))
        assertEquals(LineTarget.None, targetOfLine(listOf(FindRow(listOf(null))), 1, lineCount = 5))
    }

    @Test
    fun `the line field's number is its digits`() {
        assertEquals(42, parseLineNumber("42"))
        assertEquals(42, parseLineNumber(" 4,2 "))
        assertEquals(7, parseLineNumber("007"))
        assertEquals(0, parseLineNumber("000"))
        assertEquals(null, parseLineNumber(""))
        assertEquals(null, parseLineNumber("abc"))
        assertEquals(Int.MAX_VALUE, parseLineNumber("99999999999999"))
    }
}
