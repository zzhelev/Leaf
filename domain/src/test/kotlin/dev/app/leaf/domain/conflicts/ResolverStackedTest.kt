// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The stacked layout's rows (the sides on top, the result below), the Taken chips, and places mapped by chunk. */
class ResolverStackedTest {
    // base: a b c d e      ours: a B c d e      theirs: a b c x y e  (ours changed b, theirs replaced d)
    private val changes = TextMerge(
        text("a", "b", "c", "d", "e"),
        text("a", "B", "c", "d", "e"),
        text("a", "b", "c", "x", "y", "e"),
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

    // base: a b c     ours: a c (deleted b)     theirs: a b c n (added n)
    private val deletion = TextMerge(
        text("a", "b", "c"),
        text("a", "c"),
        text("a", "b", "c", "n"),
        listOf(
            Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
            Chunk.Change(lines(1, 2), lines(1, 1), lines(1, 2), ChangedBy.OURS),
            Chunk.Unchanged(lines(2, 3), lines(1, 2), lines(2, 3)),
            Chunk.Change(lines(3, 3), lines(2, 2), lines(3, 4), ChangedBy.THEIRS),
        ),
    )

    @Test
    fun `the top pair lines the sides up as the columns do, without the result`() {
        val rows = changes.sidesRows()

        assertEquals(listOf("a|a", "B|b", "c|c", "d|x", "_|y", "e|e"), rows.map { it.describe() })
        assertEquals(listOf(0, 1, 2, 3, 3, 4), rows.map { it.chunk })
        assertEquals(listOf(true, true, true, true, false, true), rows.map { it.first })
        assertEquals(CellKind.CHANGED_BY_OURS to CellKind.UNCHANGED, rows[1].ours.kind to rows[1].theirs.kind)
        assertEquals(listOf(1, 2, 3, 4, 0, 5), rows.map { it.ours.number })

        // A resolution doesn't change the top pair
        val open = conflict.sidesRows()

        assertEquals(listOf("1|1", "O|T", "O|_", "3|3"), open.map { it.describe() })
        assertEquals(open, conflict.resolving(1, ConflictResolution.OursThenTheirs).sidesRows())
        assertEquals(CellKind.CONFLICT, open[1].theirs.kind)
    }

    @Test
    fun `a chunk with no line on either side still gets a row on top, for its chips`() {
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

        assertEquals(listOf("a|a", "_|_"), merge.sidesRows().map { it.describe() })
        assertEquals(listOf(true, true), merge.sidesRows().map { it.first })
    }

    @Test
    fun `the result has each chunk's lines, numbered as the file that Save writes`() {
        val rows = changes.resultRows(showBase = false)

        assertEquals(listOf("a", "B", "c", "x", "y", "e"), rows.map { it.describe() })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), rows.map { it.cell.number })
        assertEquals(listOf(0, 1, 2, 3, 3, 4), rows.map { it.chunk })
        assertEquals(listOf(true, true, true, true, false, true), rows.map { it.first })
        assertEquals(
            listOf(
                CellKind.UNCHANGED, CellKind.CHANGED_BY_OURS, CellKind.UNCHANGED,
                CellKind.CHANGED_BY_THEIRS, CellKind.CHANGED_BY_THEIRS, CellKind.UNCHANGED,
            ),
            rows.map { it.cell.kind },
        )
    }

    @Test
    fun `an open conflict is one placeholder row without a number, or the base's lines when they're shown`() {
        val rows = conflict.resultRows(showBase = false)

        assertEquals(listOf("1", "_", "3"), rows.map { it.describe() })
        assertEquals(listOf(1, 0, 2), rows.map { it.cell.number })
        assertEquals(CellKind.OPEN_CONFLICT, rows[1].cell.kind)

        val withBase = conflict.resultRows(showBase = true)

        assertEquals(listOf("1", "2", "3"), withBase.map { it.describe() })
        assertEquals(listOf(1, 0, 2), withBase.map { it.cell.number })
        assertEquals(CellKind.OPEN_CONFLICT, withBase[1].cell.kind)

        // A conflict whose base has no lines still gets its placeholder with the base shown
        val added = TextMerge(
            text("1"),
            text("1", "O"),
            text("1", "T"),
            listOf(
                Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
                Chunk.Conflict(lines(1, 1), lines(1, 2), lines(1, 2)),
            ),
        )

        assertEquals(listOf("1", "_"), added.resultRows(showBase = true).map { it.describe() })
        assertEquals(CellKind.OPEN_CONFLICT, added.resultRows(showBase = true)[1].cell.kind)
    }

    @Test
    fun `a resolved conflict has its lines in the result, numbered with the rest`() {
        val rows = conflict.resolving(1, ConflictResolution.TheirsThenOurs).resultRows(showBase = true)

        assertEquals(listOf("1", "T", "O", "O", "3"), rows.map { it.describe() })
        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { it.cell.number })
        assertEquals(listOf(true, true, false, false, true), rows.map { it.first })
        assertEquals(CellKind.RESOLVED, rows[2].cell.kind)
    }

    @Test
    fun `a chunk with no line in the result gets one filler row, which marks where it is`() {
        val rows = deletion.resultRows(showBase = false)

        // Ours' deletion of b is applied; theirs' n too
        assertEquals(listOf("a", "_", "c", "n"), rows.map { it.describe() })
        assertEquals(CellKind.CHANGED_BY_OURS, rows[1].cell.kind)
        assertEquals(listOf(1, 0, 2, 3), rows.map { it.cell.number })

        // Undone, the deletion gives b back, and theirs' n leaves a mark
        val undone = deletion.applying(1, applied = false).applying(3, applied = false).resultRows(showBase = false)

        assertEquals(listOf("a", "b", "c", "_"), undone.map { it.describe() })
        assertEquals(CellKind.UNCHANGED, undone[3].cell.kind)
        assertEquals(listOf(1, 2, 3, 0), undone.map { it.cell.number })
    }

    @Test
    fun `the result's lines and numbers are the columns' result, without the fillers`() {
        val merges = listOf(changes, deletion, conflict) + resolutions.map { conflict.resolving(1, it) }

        for (merge in merges) {
            for (showBase in listOf(false, true)) {
                fun List<ColumnCell>.lines() = filter { it.text != null }.map { it.text!!.getString(it.line) to it.number }

                assertEquals(
                    merge.columnRows(showBase).map { it.result }.lines(),
                    merge.resultRows(showBase).map { it.cell }.lines(),
                )
            }
        }
    }

    @Test
    fun `a side's Taken chip takes its lines out of the result, and the other side's stay`() {
        val ours = ConflictSide.OURS
        val theirs = ConflictSide.THEIRS

        assertEquals(ConflictResolution.Unresolved, ConflictResolution.Ours.dropping(ours))
        assertSame(ConflictResolution.Ours, ConflictResolution.Ours.dropping(theirs))
        assertEquals(ConflictResolution.Unresolved, ConflictResolution.Theirs.dropping(theirs))
        assertSame(ConflictResolution.Theirs, ConflictResolution.Theirs.dropping(ours))

        for (both in listOf(ConflictResolution.OursThenTheirs, ConflictResolution.TheirsThenOurs)) {
            assertEquals(ConflictResolution.Theirs, both.dropping(ours))
            assertEquals(ConflictResolution.Ours, both.dropping(theirs))
        }

        // Nothing of either side is in these
        val edited = ConflictResolution.Edited(TypedLines(text("x")))

        for (other in listOf(ConflictResolution.Unresolved, ConflictResolution.Base, edited)) {
            assertSame(other, other.dropping(ours))
            assertSame(other, other.dropping(theirs))
        }

        // Taking a side, then dropping it, goes back
        for (side in ConflictSide.entries) {
            assertEquals(ConflictResolution.Unresolved, ConflictResolution.Unresolved.taking(side).dropping(side))
        }

        assertEquals(ConflictResolution.Ours, ConflictResolution.Ours.taking(theirs).dropping(theirs))
        assertEquals(ConflictResolution.Theirs, ConflictResolution.Theirs.taking(ours).dropping(ours))
    }

    @Test
    fun `a place is a chunk and a row of it, and maps to the same row or the chunk's last`() {
        val rowChunks = listOf(0, 1, 1, 1, 3, 3, 4)

        assertEquals(ChunkPlace(1, 1), placeOf(rowChunks, 2))
        assertEquals(ChunkPlace(3, 0), placeOf(rowChunks, 4))
        assertEquals(ChunkPlace(4, 0), placeOf(rowChunks, 6))
        assertNull(placeOf(rowChunks, 7))
        assertNull(placeOf(rowChunks, -1))

        for (index in rowChunks.indices) {
            assertEquals(index, rowOf(rowChunks, placeOf(rowChunks, index)!!))
        }

        // A chunk with fewer rows: its last
        assertEquals(3, rowOf(rowChunks, ChunkPlace(1, 7)))
        // A chunk without rows: the next chunk's first, or the last row when none follows
        assertEquals(4, rowOf(rowChunks, ChunkPlace(2, 1)))
        assertEquals(6, rowOf(rowChunks, ChunkPlace(5, 0)))
        assertEquals(0, rowOf(emptyList(), ChunkPlace(3, 2)))
    }

    @Test
    fun `switching layouts keeps the chunk, and the row in it where the other layout has it`() {
        val merge = conflict.resolving(1, ConflictResolution.TheirsThenOurs)
        val columns = merge.columnRows(showBase = false).map { it.chunk }
        val result = merge.resultRows(showBase = false).map { it.chunk }
        val sides = merge.sidesRows().map { it.chunk }

        // The columns' third row of the conflict is the result's third, and the top pair's last (ours has two)
        val place = placeOf(columns, 3)!!

        assertEquals(ChunkPlace(1, 2), place)
        assertEquals(3, rowOf(result, place))
        assertEquals(2, rowOf(sides, place))
        // The last line, from either
        assertEquals(4, rowOf(result, placeOf(columns, 4)!!))
        assertEquals(3, rowOf(sides, placeOf(result, 4)!!))
    }
}

/** Each kind of resolution of a conflict, an edited one included. */
private val resolutions = listOf(
    ConflictResolution.Unresolved,
    ConflictResolution.Ours,
    ConflictResolution.Theirs,
    ConflictResolution.OursThenTheirs,
    ConflictResolution.TheirsThenOurs,
    ConflictResolution.Base,
    ConflictResolution.Edited(TypedLines(text("E", "F"))),
)

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)

/** The row's two cells as `ours|theirs`, with `_` for a filler. */
private fun SidesRow.describe() = listOf(ours, theirs).joinToString("|") { cell -> cell.text?.getString(cell.line) ?: "_" }

private fun ResultRow.describe() = cell.text?.getString(cell.line) ?: "_"
