// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.find

import dev.app.leaf.domain.find.FindMatch
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A diff's text for find (upstream issues 275 and 312): its rows, the lines they draw, its length and what Copy gives. */
class DiffFindTextTest {
    private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

    @Test
    fun `the rows and the lines they draw go together, a header first, in both views`() {
        for (diff in listOf(hunksDiff, split(hunksDiff), fullDiff, split(fullDiff))) {
            val find = DiffFindText(diff)

            assertEquals(find.rows.size, find.rowLines.size)
            assertEquals(listOf(null), find.rowLines.first().distinct())

            for ((row, lines) in find.rows.zip(find.rowLines)) {
                assertEquals(row.cells.size, lines.size)
                assertEquals(row.cells.map { it == null }, lines.map { it == null })
            }
        }
    }

    @Test
    fun `go to line counts the new version's lines, or a deleted file's old ones`() {
        val added = Hunk("@@", listOf(Line("a\n", 0, 0, LineType.ADDED), Line("b\n", 0, 1, LineType.ADDED)), text(), text("a", "b"))
        val deleted = Hunk("@@", listOf(Line("x\n", 0, 0, LineType.REMOVED)), text("x", "y", "z"), text())
        val grown = Hunk("@@", listOf(Line("x\n", 0, 0, LineType.CONTEXT)), text("x"), text("x", "y", "z", "w"))

        assertEquals(2, DiffFindText(DiffResult.Text(hunksDiff.diffEntry, listOf(added))).lineCount)
        assertEquals(3, DiffFindText(DiffResult.Text(hunksDiff.diffEntry, listOf(deleted))).lineCount)
        assertEquals(4, DiffFindText(DiffResult.Text(hunksDiff.diffEntry, listOf(grown))).lineCount)
        assertEquals(0, DiffFindText(DiffResult.Text(hunksDiff.diffEntry, emptyList())).lineCount)
        assertFalse(DiffFindText(hunksDiff).isSplit)
        assertTrue(DiffFindText(split(hunksDiff)).isSplit)
    }

    @Test
    fun `a split diff's marks tell a context line's two sides apart, and go to line marks the new side's line`() {
        val find = DiffFindText(split(hunksDiff))
        val context = find.rowLines[1][0]!!
        assertSame(context, find.rowLines[1][1])

        val marks = DiffFindMarks.of(
            rowLines = find.rowLines,
            matches = listOf(FindMatch(1, 1, 0, 4)),
            current = FindMatch(1, 1, 0, 4),
            isSplit = true,
            goToRow = 6,
            selectedCell = 0,
        )

        assertEquals(LineFindMarks(isSelected = true), marks.marksOf(context, isOldSide = true))
        assertEquals(LineFindMarks(listOf(0 until 4), 0 until 4, isSelected = false), marks.marksOf(context, isOldSide = false))
        // Row 6 pairs the removed and the added line 6: the added one has the number
        assertTrue(marks.isGoToLine(find.rowLines[6][1]!!))
        assertFalse(marks.isGoToLine(find.rowLines[6][0]!!))
    }

    @Test
    fun `Copy gives each side's lines without their line endings`() {
        val find = DiffFindText(split(hunksDiff))

        assertEquals("line 1", find.copyText(0).lines().first())
        assertEquals(20, find.copyText(0).lines().size)
        assertEquals(find.copyText(1).lines(), find.copyText(1).lines().map { it.trimEnd('\n', '\r') })
        assertTrue("val old = 0" in find.copyText(0).lines())
        assertTrue("val parser = Parser()" in find.copyText(1).lines())
    }
}
