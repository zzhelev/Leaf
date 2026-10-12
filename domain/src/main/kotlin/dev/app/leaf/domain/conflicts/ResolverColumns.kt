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
}

/**
 * A cell of a column: line [line] of [text] (counted from 0), shown as line [number] (0 for none), or a filler where
 * that column has no line in the row ([text] null).
 */
data class ColumnCell(val text: RawText?, val line: Int, val number: Int, val kind: CellKind) {
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

        val result = resultLines(chunk, showBase)
        // An open conflict's lines aren't in the result yet, so they get no number
        val resultCells = result.lines.mapIndexed { i, (text, line) ->
            ColumnCell(text, line, if (result.kind == CellKind.OPEN_CONFLICT) 0 else resultLines + i + 1, result.kind)
        }

        if (result.kind != CellKind.OPEN_CONFLICT) {
            resultLines += result.lines.size
        }

        // A chunk with no line in any column still gets a row, for its gutter: lines that both sides deleted, or an
        // open conflict between two deletions
        val height = maxOf(oursCells.size, theirsCells.size, resultCells.size, if (chunk is Chunk.Unchanged) 0 else 1)

        for (row in 0 until height) {
            rows += ColumnRow(
                chunk = index,
                first = row == 0,
                ours = oursCells.getOrNull(row) ?: filler(oursKind),
                result = resultCells.getOrNull(row) ?: filler(result.kind),
                theirs = theirsCells.getOrNull(row) ?: filler(theirsKind),
            )
        }
    }

    return rows
}

private fun cells(text: RawText, range: LineRange, kind: CellKind) =
    (range.start until range.end).map { ColumnCell(text, it, it + 1, kind) }

private fun filler(kind: CellKind) = ColumnCell(text = null, line = 0, number = 0, kind = kind)

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

private class ResultLines(val lines: List<Pair<RawText, Int>>, val kind: CellKind)

private fun TextMerge.resultLines(chunk: Chunk, showBase: Boolean): ResultLines {
    fun lines(text: RawText, range: LineRange) = (range.start until range.end).map { text to it }

    return when (chunk) {
        is Chunk.Unchanged -> ResultLines(lines(base, chunk.base), CellKind.UNCHANGED)
        is Chunk.Change -> when {
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
                is ConflictResolution.Edited -> lines(resolution.text, LineRange(0, resolution.text.size()))
            }

            when {
                resolved != null -> ResultLines(resolved, CellKind.RESOLVED)
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

/** The chunks' indices of the conflicts, in the file's order. */
fun TextMerge.conflictChunks(): List<Int> = chunks.indices.filter { chunks[it] is Chunk.Conflict }

/** The merge with [chunk]'s conflict resolved as [resolution]. */
fun TextMerge.resolving(chunk: Int, resolution: ConflictResolution): TextMerge {
    val conflict = chunks[chunk] as? Chunk.Conflict ?: return this

    return copy(chunks = chunks.toMutableList().apply { set(chunk, conflict.copy(resolution = resolution)) })
}

/** The merge with [chunk]'s change applied or not. */
fun TextMerge.applying(chunk: Int, applied: Boolean): TextMerge {
    val change = chunks[chunk] as? Chunk.Change ?: return this

    return copy(chunks = chunks.toMutableList().apply { set(chunk, change.copy(applied = applied)) })
}
