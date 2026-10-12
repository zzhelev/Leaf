// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The resolver's three columns, row by row, and what its arrows do. */
class ResolverColumnsTest {
    // base: a b c d e      ours: a B c d e      theirs: a b c d x y e  (ours changed b, theirs replaced d)
    private val base = text("a", "b", "c", "d", "e")
    private val ours = text("a", "B", "c", "d", "e")
    private val theirs = text("a", "b", "c", "x", "y", "e")
    private val changes = TextMerge(
        base, ours, theirs,
        listOf(
            Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
            Chunk.Change(lines(1, 2), lines(1, 2), lines(1, 2), ChangedBy.OURS),
            Chunk.Unchanged(lines(2, 3), lines(2, 3), lines(2, 3)),
            Chunk.Change(lines(3, 4), lines(3, 4), lines(3, 5), ChangedBy.THEIRS),
            Chunk.Unchanged(lines(4, 5), lines(4, 5), lines(5, 6)),
        ),
    )

    // base: 1 2 3     ours: 1 O O 3     theirs: 1 T 3
    private val conflict = TextMerge(
        text("1", "2", "3"),
        text("1", "O", "O", "3"),
        text("1", "T", "3"),
        listOf(
            Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
            Chunk.Conflict(lines(1, 2), lines(1, 3), lines(1, 2)),
            Chunk.Unchanged(lines(2, 3), lines(3, 4), lines(2, 3)),
        ),
    )

    @Test
    fun `each chunk takes the rows of its tallest column, and the others get fillers`() {
        assertEquals(
            listOf(
                "a|a|a" to Triple(CellKind.UNCHANGED, CellKind.UNCHANGED, CellKind.UNCHANGED),
                "B|B|b" to Triple(CellKind.CHANGED_BY_OURS, CellKind.CHANGED_BY_OURS, CellKind.UNCHANGED),
                "c|c|c" to Triple(CellKind.UNCHANGED, CellKind.UNCHANGED, CellKind.UNCHANGED),
                "d|x|x" to Triple(CellKind.UNCHANGED, CellKind.CHANGED_BY_THEIRS, CellKind.CHANGED_BY_THEIRS),
                "_|y|y" to Triple(CellKind.UNCHANGED, CellKind.CHANGED_BY_THEIRS, CellKind.CHANGED_BY_THEIRS),
                "e|e|e" to Triple(CellKind.UNCHANGED, CellKind.UNCHANGED, CellKind.UNCHANGED),
            ),
            changes.columnRows(showBase = false).map { it.describe() to it.kinds() },
        )
    }

    @Test
    fun `the result is numbered as the file that Save writes, and the sides as their versions`() {
        val rows = changes.columnRows(showBase = false)

        assertEquals(listOf(1, 2, 3, 4, 5, 6), rows.map { it.result.number })
        assertEquals(listOf(1, 2, 3, 4, 0, 5), rows.map { it.ours.number })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), rows.map { it.theirs.number })
        assertEquals(listOf(true, true, true, true, false, true), rows.map { it.first })
    }

    @Test
    fun `a change that isn't applied shows the base's lines in the result`() {
        val rows = changes.applying(chunk = 3, applied = false).columnRows(showBase = false)

        assertEquals(listOf("a|a|a", "B|B|b", "c|c|c", "d|d|x", "_|_|y", "e|e|e"), rows.map { it.describe() })
        assertEquals(CellKind.UNCHANGED, rows[3].result.kind)
    }

    @Test
    fun `an open conflict is a placeholder without line numbers, or the base's lines when they're shown`() {
        val rows = conflict.columnRows(showBase = false)

        assertEquals(listOf("1|1|1", "O|_|T", "O|_|_", "3|3|3"), rows.map { it.describe() })
        assertEquals(CellKind.OPEN_CONFLICT, rows[1].result.kind)
        assertEquals(listOf(1, 2), rows.map { it.result.number }.filter { it != 0 })

        val withBase = conflict.columnRows(showBase = true)

        assertEquals(listOf("1|1|1", "O|2|T", "O|_|_", "3|3|3"), withBase.map { it.describe() })
        assertEquals(0, withBase[1].result.number)
    }

    @Test
    fun `a resolved conflict has its lines in the result, numbered with the rest`() {
        val rows = conflict.resolving(chunk = 1, ConflictResolution.TheirsThenOurs).columnRows(showBase = true)

        assertEquals(listOf("1|1|1", "O|T|T", "O|O|_", "_|O|_", "3|3|3"), rows.map { it.describe() })
        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { it.result.number })
        assertEquals(CellKind.RESOLVED, rows[1].result.kind)
    }

    @Test
    fun `a chunk with no lines in any column still gets a row for its gutter`() {
        // Both deleted b
        val merge = TextMerge(
            text("a", "b"),
            text("a"),
            text("a"),
            listOf(
                Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
                Chunk.Change(lines(1, 2), lines(1, 1), lines(1, 1), ChangedBy.BOTH),
            ),
        )

        assertEquals(listOf("a|a|a", "_|_|_"), merge.columnRows(showBase = false).map { it.describe() })
        assertEquals(listOf("a|a|a", "_|b|_"), merge.applying(1, applied = false).columnRows(false).map { it.describe() })
    }

    @Test
    fun `an arrow takes its side, then the other side's adds after it`() {
        val open = ConflictResolution.Unresolved

        assertEquals(ConflictArrow.TAKE, open.arrowFor(ConflictSide.OURS))
        assertEquals(ConflictResolution.Ours, open.taking(ConflictSide.OURS))
        assertEquals(ConflictResolution.Theirs, open.taking(ConflictSide.THEIRS))

        val ours = ConflictResolution.Ours

        assertNull(ours.arrowFor(ConflictSide.OURS))
        assertEquals(ConflictArrow.ADD_AFTER, ours.arrowFor(ConflictSide.THEIRS))
        assertEquals(ConflictResolution.OursThenTheirs, ours.taking(ConflictSide.THEIRS))
        assertEquals(ConflictResolution.TheirsThenOurs, ConflictResolution.Theirs.taking(ConflictSide.OURS))

        for (both in listOf(ConflictResolution.OursThenTheirs, ConflictResolution.TheirsThenOurs)) {
            assertNull(both.arrowFor(ConflictSide.OURS))
            assertNull(both.arrowFor(ConflictSide.THEIRS))
            assertSame(both, both.taking(ConflictSide.OURS))
        }

        // The base, or typed lines, are replaced by the side taken
        assertEquals(ConflictResolution.Theirs, ConflictResolution.Base.taking(ConflictSide.THEIRS))
        assertEquals(ConflictArrow.TAKE, ConflictResolution.Edited(text("x")).arrowFor(ConflictSide.OURS))
    }

    @Test
    fun `resolving and applying change only their kind of chunk`() {
        assertEquals(listOf(1), conflict.conflictChunks())
        assertSame(conflict, conflict.applying(1, applied = false))
        assertSame(changes, changes.resolving(1, ConflictResolution.Ours))
        assertEquals(
            ConflictResolution.Base,
            (conflict.resolving(1, ConflictResolution.Base).chunks[1] as Chunk.Conflict).resolution,
        )
    }
}

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)

/** The row's three cells as `ours|result|theirs`, with `_` for a filler. */
private fun ColumnRow.describe() = listOf(ours, result, theirs).joinToString("|") { cell ->
    cell.text?.getString(cell.line) ?: "_"
}

private fun ColumnRow.kinds() = Triple(ours.kind, result.kind, theirs.kind)
