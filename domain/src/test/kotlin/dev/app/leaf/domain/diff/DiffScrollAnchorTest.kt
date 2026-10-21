// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.diff

import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.SplitHunk
import io.mockk.mockk
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which row of a diff's list shows the line that another diff of the same file had at the top. */
class DiffScrollAnchorTest {
    private val entry = mockk<DiffEntry>(relaxed = true)

    private fun context(line: Int) = Line("line $line\n", line, line, LineType.CONTEXT)

    private fun hunk(lines: List<Line>) = Hunk("@@", lines, RawText.EMPTY_TEXT, RawText.EMPTY_TEXT)

    /** Every line from 0 to 99, in one hunk: a header row, then a row per line. */
    private val everyLine = DiffResult.Text(entry, listOf(hunk((0 until 100).map(::context))))

    /** What ignoring whitespace leaves of it: two hunks, around lines 10 and 70. */
    private val someLines = DiffResult.Text(
        entry,
        listOf(
            hunk((8..14).map(::context)),
            hunk((68..74).map(::context)),
        ),
    )

    @Test
    fun `a row's anchor is its line of the new version, and a header's is its hunk's first`() {
        assertEquals(DiffScrollAnchor(0), everyLine.anchorAt(0), "The header")
        assertEquals(DiffScrollAnchor(0), everyLine.anchorAt(1))
        assertEquals(DiffScrollAnchor(50), everyLine.anchorAt(51))
        assertEquals(DiffScrollAnchor(68), someLines.anchorAt(8), "The second hunk's header")
        assertEquals(DiffScrollAnchor(69), someLines.anchorAt(10))
    }

    @Test
    fun `a row past the end is the last, and a diff with no rows has no anchor`() {
        assertEquals(DiffScrollAnchor(99), everyLine.anchorAt(5_000))
        assertEquals(DiffScrollAnchor(0), everyLine.anchorAt(-3), "A negative row is the first")
        assertNull(DiffResult.Text(entry, emptyList()).anchorAt(0))
    }

    @Test
    fun `the row of a line is the first row at or after it`() {
        assertEquals(51, everyLine.rowAt(DiffScrollAnchor(50)))
        assertEquals(0, everyLine.rowAt(DiffScrollAnchor(0)), "A hunk's header comes before its first line")
        // Line 30 is hidden: the next one that is shown is the second hunk's header
        assertEquals(8, someLines.rowAt(DiffScrollAnchor(30)))
        assertEquals(2, someLines.rowAt(DiffScrollAnchor(9)))
        assertEquals(15, someLines.rowAt(DiffScrollAnchor(500)), "Past every line: the last row")
        assertEquals(0, DiffResult.Text(entry, emptyList()).rowAt(DiffScrollAnchor(5)))
    }

    @Test
    fun `the line at the top stays there when the rows change, either way`() {
        // Row 51 of the full diff shows line 50. Ignoring whitespace hides it, and the next shown line is 68's header
        assertEquals(8, rowKeptAcross(everyLine, 51, someLines))
        // Line 70 at row 11 of the short one is row 71 of the full one
        assertEquals(71, rowKeptAcross(someLines, 11, everyLine))
        // The same diff: the same row
        assertEquals(51, rowKeptAcross(everyLine, 51, everyLine))
    }

    @Test
    fun `a diff that isn't text has nothing to keep`() {
        val binary = DiffResult.NonText(entry, mockk(), mockk())

        assertNull(rowKeptAcross(binary, 3, everyLine))
        assertNull(rowKeptAcross(everyLine, 3, binary))
        assertNull(rowKeptAcross(null, 3, everyLine))
        assertNull(rowKeptAcross(DiffResult.Text(entry, emptyList()), 3, everyLine), "No rows to take the top line from")
    }

    @Test
    fun `a split diff has a row per pair, with the new side's line`() {
        val removed = Line("old\n", 20, 20, LineType.REMOVED)
        val added = Line("new\n", 20, 20, LineType.ADDED)
        val split = DiffResult.TextSplit(
            entry,
            listOf(
                SplitHunk(
                    hunk(listOf(context(19), removed, added, context(21))),
                    listOf(context(19) to context(19), removed to added, null to Line("only new\n", 21, 21, LineType.ADDED), context(21) to context(21)),
                ),
            ),
        )

        // A header, then a row for each pair
        assertEquals(DiffScrollAnchor(19), split.anchorAt(0))
        assertEquals(DiffScrollAnchor(20), split.anchorAt(2))
        assertEquals(DiffScrollAnchor(21), split.anchorAt(3))
        assertEquals(2, split.rowAt(DiffScrollAnchor(20)))
        // Two lines replaced by two: the removed lines are at line 20 of the new version, where the block starts
        val removed2 = Line("old 2\n", 21, 20, LineType.REMOVED)
        val added2 = Line("new 2\n", 21, 21, LineType.ADDED)
        val replaced = DiffResult.TextSplit(
            entry,
            listOf(
                SplitHunk(
                    hunk(listOf(removed, removed2, added, added2)),
                    listOf(removed to added, removed2 to added2),
                ),
            ),
        )

        assertEquals(DiffScrollAnchor(20), replaced.anchorAt(1))
        assertEquals(DiffScrollAnchor(21), replaced.anchorAt(2), "The new side's line, not the removed line's")

        // Unified and split of the same file agree on the line
        assertEquals(
            split.rowAt(DiffScrollAnchor(21)),
            rowKeptAcross(DiffResult.Text(entry, listOf(hunk(listOf(context(19), removed, added, context(21))))), 4, split),
        )
    }
}
