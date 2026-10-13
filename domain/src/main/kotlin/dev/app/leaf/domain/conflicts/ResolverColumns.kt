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

/** How a cell of the resolver's columns is coloured: what its chunk is, on that column. */
enum class CellKind {
    /** The same in the three versions. */
    UNCHANGED,
    CHANGED_BY_OURS,
    CHANGED_BY_THEIRS,
    CHANGED_BY_BOTH,

    /** Ours' or theirs' side of a conflict. */
    CONFLICT,

    /** The result of a conflict that is still open: a placeholder, or the base's lines when they're shown. */
    OPEN_CONFLICT,

    /** The result of a conflict that has a resolution. */
    RESOLVED,

    /** Lines typed in place of lines that weren't a conflict: unchanged lines, or a change. */
    EDITED,
}

/**
 * A cell of a column: line [line] of [text] (counted from 0), shown as line [number] (0 for none), or a filler where
 * that column has no line in the row ([text] null). [edited]: a cell of the result, in a part that the user typed.
 */
data class ColumnCell(
    val text: RawText?,
    val line: Int,
    val number: Int,
    val kind: CellKind,
    val edited: Boolean = false,
) {
    val isFiller get() = text == null
}

/**
 * A row of the resolver's columns (`conflict-resolver.md`, Option A): ours, the result and theirs side by side, so that
 * they scroll together. A chunk takes as many rows as its tallest column, and the other columns get fillers. The
 * gutters' buttons go on its [first] row.
 */
data class ColumnRow(
    val chunk: Int,
    val first: Boolean,
    val ours: ColumnCell,
    val result: ColumnCell,
    val theirs: ColumnCell,
)

/**
 * The rows of the three columns. The result has each chunk's lines as its state makes them, numbered as in the file
 * that Save writes, except that an open conflict shows a placeholder, or the base's lines with [showBase].
 */
fun TextMerge.columnRows(showBase: Boolean): List<ColumnRow> {
    val rows = mutableListOf<ColumnRow>()
    var resultLines = 0

    chunks.forEachIndexed { index, chunk ->
        val oursKind = sideKind(chunk, ChangedBy.OURS)
        val theirsKind = sideKind(chunk, ChangedBy.THEIRS)
        val oursCells = cells(ours, chunk.ours, oursKind)
        val theirsCells = cells(theirs, chunk.theirs, theirsKind)

        val result = resultCells(chunk, showBase, resultLines)
        resultLines += result.numbered

        // A chunk with no line in any column still gets a row, for its gutter: lines that both sides deleted, or an
        // open conflict between two deletions
        val height = maxOf(oursCells.size, theirsCells.size, result.cells.size, minimumHeight(chunk))

        for (row in 0 until height) {
            rows += ColumnRow(
                chunk = index,
                first = row == 0,
                ours = oursCells.getOrNull(row) ?: filler(oursKind),
                result = result.cells.getOrNull(row) ?: filler(result.kind, result.edited),
                theirs = theirsCells.getOrNull(row) ?: filler(theirsKind),
            )
        }
    }

    return rows
}

/**
 * A row of the stacked layout's top pair (`conflict-resolver.md`, Option B): ours and theirs side by side, lined up as
 * in the columns. A chunk takes as many rows as its taller side, and its chips go on its [first] row.
 */
data class SidesRow(val chunk: Int, val first: Boolean, val ours: ColumnCell, val theirs: ColumnCell)

/** The rows of the stacked layout's top pair. */
fun TextMerge.sidesRows(): List<SidesRow> {
    val rows = mutableListOf<SidesRow>()

    chunks.forEachIndexed { index, chunk ->
        val oursKind = sideKind(chunk, ChangedBy.OURS)
        val theirsKind = sideKind(chunk, ChangedBy.THEIRS)
        val oursCells = cells(ours, chunk.ours, oursKind)
        val theirsCells = cells(theirs, chunk.theirs, theirsKind)
        // A chunk that neither side has a line of still gets a row, for its chips
        val height = maxOf(oursCells.size, theirsCells.size, minimumHeight(chunk))

        for (row in 0 until height) {
            rows += SidesRow(
                chunk = index,
                first = row == 0,
                ours = oursCells.getOrNull(row) ?: filler(oursKind),
                theirs = theirsCells.getOrNull(row) ?: filler(theirsKind),
            )
        }
    }

    return rows
}

/**
 * A row of the stacked layout's result, which has the full width: a line of the result, as in [columnRows], or a
 * filler. A chunk with no line in the result gets one filler row, which marks where it is: lines that were deleted, or
 * an open conflict's placeholder.
 */
data class ResultRow(val chunk: Int, val first: Boolean, val cell: ColumnCell)

/** The rows of the stacked layout's result. */
fun TextMerge.resultRows(showBase: Boolean): List<ResultRow> {
    val rows = mutableListOf<ResultRow>()
    var resultLines = 0

    chunks.forEachIndexed { index, chunk ->
        val result = resultCells(chunk, showBase, resultLines)
        resultLines += result.numbered

        val cells = result.cells.ifEmpty {
            // Lines deleted by typing get one too
            if (minimumHeight(chunk) > 0 || result.edited) listOf(filler(result.kind, result.edited)) else emptyList()
        }

        cells.forEachIndexed { row, cell -> rows += ResultRow(chunk = index, first = row == 0, cell = cell) }
    }

    return rows
}

private fun minimumHeight(chunk: Chunk) = if (chunk is Chunk.Unchanged) 0 else 1

private fun cells(text: RawText, range: LineRange, kind: CellKind) =
    (range.start until range.end).map { ColumnCell(text, it, it + 1, kind) }

private fun filler(kind: CellKind, edited: Boolean = false) =
    ColumnCell(text = null, line = 0, number = 0, kind = kind, edited = edited)

private fun sideKind(chunk: Chunk, side: ChangedBy): CellKind = when (chunk) {
    is Chunk.Unchanged -> CellKind.UNCHANGED
    is Chunk.Conflict -> CellKind.CONFLICT
    is Chunk.Change -> when (chunk.by) {
        ChangedBy.BOTH -> CellKind.CHANGED_BY_BOTH
        side -> if (side == ChangedBy.OURS) CellKind.CHANGED_BY_OURS else CellKind.CHANGED_BY_THEIRS
        // The side that didn't make the change has the base's lines
        else -> CellKind.UNCHANGED
    }
}

/** A chunk's lines in the result, as lines of a version or of what the user typed ([edited]). */
internal class ResultLines(val lines: List<Pair<RawText, Int>>, val kind: CellKind, val edited: Boolean = false)

/** A chunk's cells in the result, and how many lines of the file that Save writes they are ([numbered]). */
private class ResultCells(val cells: List<ColumnCell>, val kind: CellKind, val numbered: Int, val edited: Boolean)

/** [chunk]'s cells in the result, numbered after [linesBefore]. An open conflict's lines aren't in it yet. */
private fun TextMerge.resultCells(chunk: Chunk, showBase: Boolean, linesBefore: Int): ResultCells {
    val result = resultLines(chunk, showBase)
    val open = result.kind == CellKind.OPEN_CONFLICT
    val cells = result.lines.mapIndexed { i, (text, line) ->
        ColumnCell(text, line, if (open) 0 else linesBefore + i + 1, result.kind, result.edited)
    }

    return ResultCells(cells, result.kind, if (open) 0 else cells.size, result.edited)
}

internal fun lines(text: RawText, range: LineRange) = (range.start until range.end).map { text to it }

internal fun lines(typed: TypedLines) = lines(typed.text, LineRange(0, typed.size))

/** [chunk]'s lines in the result. An open conflict has none, or the base's lines with [showBase]. */
internal fun TextMerge.resultLines(chunk: Chunk, showBase: Boolean): ResultLines {
    return when (chunk) {
        is Chunk.Unchanged -> when (val edited = chunk.edited) {
            null -> ResultLines(lines(base, chunk.base), CellKind.UNCHANGED)
            else -> ResultLines(lines(edited), CellKind.EDITED, edited = true)
        }

        is Chunk.Change -> when {
            chunk.edited != null -> ResultLines(lines(chunk.edited), CellKind.EDITED, edited = true)
            !chunk.applied -> ResultLines(lines(base, chunk.base), CellKind.UNCHANGED)
            chunk.by == ChangedBy.OURS -> ResultLines(lines(ours, chunk.ours), CellKind.CHANGED_BY_OURS)
            chunk.by == ChangedBy.THEIRS -> ResultLines(lines(theirs, chunk.theirs), CellKind.CHANGED_BY_THEIRS)
            else -> ResultLines(lines(ours, chunk.ours), CellKind.CHANGED_BY_BOTH)
        }

        is Chunk.Conflict -> {
            val oursLines = lines(ours, chunk.ours)
            val theirsLines = lines(theirs, chunk.theirs)
            val resolved = when (val resolution = chunk.resolution) {
                ConflictResolution.Unresolved -> null
                ConflictResolution.Ours -> oursLines
                ConflictResolution.Theirs -> theirsLines
                ConflictResolution.OursThenTheirs -> oursLines + theirsLines
                ConflictResolution.TheirsThenOurs -> theirsLines + oursLines
                ConflictResolution.Base -> lines(base, chunk.base)
                is ConflictResolution.Edited -> lines(resolution.lines)
            }

            when {
                resolved != null -> ResultLines(resolved, CellKind.RESOLVED, chunk.resolution is ConflictResolution.Edited)
                showBase -> ResultLines(lines(base, chunk.base), CellKind.OPEN_CONFLICT)
                else -> ResultLines(emptyList(), CellKind.OPEN_CONFLICT)
            }
        }
    }
}

/** What a side's arrow does to a conflict, or nothing, when the result already has that side. */
enum class ConflictArrow {
    /** Takes that side's lines, in place of what the result has. */
    TAKE,

    /** Adds that side's lines after the other side's, which the result has. */
    ADD_AFTER,
}

/**
 * The arrow that [side]'s gutter shows for a conflict with this resolution (`conflict-resolver.md`, Option A): once one
 * side is taken, the other side's arrow adds its lines after it, so both in either order is two clicks.
 */
fun ConflictResolution.arrowFor(side: ConflictSide): ConflictArrow? = when (this) {
    ConflictResolution.Ours -> if (side == ConflictSide.THEIRS) ConflictArrow.ADD_AFTER else null
    ConflictResolution.Theirs -> if (side == ConflictSide.OURS) ConflictArrow.ADD_AFTER else null
    ConflictResolution.OursThenTheirs, ConflictResolution.TheirsThenOurs -> null
    ConflictResolution.Unresolved, ConflictResolution.Base, is ConflictResolution.Edited -> ConflictArrow.TAKE
}

/** The resolution after [side]'s arrow: see [arrowFor]. */
fun ConflictResolution.taking(side: ConflictSide): ConflictResolution = when (this) {
    ConflictResolution.Ours -> if (side == ConflictSide.THEIRS) ConflictResolution.OursThenTheirs else this
    ConflictResolution.Theirs -> if (side == ConflictSide.OURS) ConflictResolution.TheirsThenOurs else this
    ConflictResolution.OursThenTheirs, ConflictResolution.TheirsThenOurs -> this
    ConflictResolution.Unresolved, ConflictResolution.Base, is ConflictResolution.Edited ->
        if (side == ConflictSide.OURS) ConflictResolution.Ours else ConflictResolution.Theirs
}

/**
 * The resolution without [side]'s lines, after a click on that side's Taken chip in the stacked layout: the other
 * side's alone, or the conflict open again.
 */
fun ConflictResolution.dropping(side: ConflictSide): ConflictResolution = when (this) {
    ConflictResolution.Ours -> if (side == ConflictSide.OURS) ConflictResolution.Unresolved else this
    ConflictResolution.Theirs -> if (side == ConflictSide.THEIRS) ConflictResolution.Unresolved else this
    ConflictResolution.OursThenTheirs, ConflictResolution.TheirsThenOurs ->
        if (side == ConflictSide.OURS) ConflictResolution.Theirs else ConflictResolution.Ours
    ConflictResolution.Unresolved, ConflictResolution.Base, is ConflictResolution.Edited -> this
}

/**
 * A place in the file, as rows of either layout show it: a chunk, and a row of it, counted from 0. Switching layouts
 * keeps it, and the stacked layout's top pair follows the result by it.
 */
data class ChunkPlace(val chunk: Int, val row: Int)

/** The place of row [index] in rows whose chunks are [rowChunks], in the order of the chunks. */
fun placeOf(rowChunks: List<Int>, index: Int): ChunkPlace? {
    val chunk = rowChunks.getOrNull(index) ?: return null

    return ChunkPlace(chunk, index - firstRowFrom(rowChunks, chunk))
}

/**
 * The row of [place] in rows whose chunks are [rowChunks]: the chunk's last one when it has fewer rows here, and the
 * first row of the next chunk that has rows when it has none.
 */
fun rowOf(rowChunks: List<Int>, place: ChunkPlace): Int {
    val first = firstRowFrom(rowChunks, place.chunk)
    val end = firstRowFrom(rowChunks, place.chunk + 1)

    if (first == end) {
        return first.coerceAtMost(rowChunks.lastIndex).coerceAtLeast(0)
    }

    return (first + place.row).coerceIn(first, end - 1)
}

/** The first row of [chunk], or of the first chunk after it, in rows sorted by chunk (a binary search). */
private fun firstRowFrom(rowChunks: List<Int>, chunk: Int): Int {
    var low = 0
    var high = rowChunks.size

    while (low < high) {
        val middle = (low + high) ushr 1

        if (rowChunks[middle] < chunk) low = middle + 1 else high = middle
    }

    return low
}

/** The chunks' indices of the conflicts, in the file's order. */
fun TextMerge.conflictChunks(): List<Int> = chunks.indices.filter { chunks[it] is Chunk.Conflict }

/** The merge with [chunk]'s conflict resolved as [resolution]. */
fun TextMerge.resolving(chunk: Int, resolution: ConflictResolution): TextMerge {
    val conflict = chunks[chunk] as? Chunk.Conflict ?: return this

    return copy(chunks = chunks.toMutableList().apply { set(chunk, conflict.copy(resolution = resolution)) })
}

/** The merge with [chunk]'s change applied or not, in place of lines typed there. */
fun TextMerge.applying(chunk: Int, applied: Boolean): TextMerge {
    val change = chunks[chunk] as? Chunk.Change ?: return this

    return copy(chunks = chunks.toMutableList().apply { set(chunk, change.copy(applied = applied, edited = null)) })
}
