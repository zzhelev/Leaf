// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.find

import dev.app.leaf.domain.extensions.removeLineDelimiters
import dev.app.leaf.domain.extensions.replaceTabs
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.SplitHunk

/**
 * The rows of a unified diff's list, as the diff pane lays them out: for each hunk a header row, with nothing to find,
 * then a row for each of its lines. A line's text is what [shownText] draws, up to [maxCharacters].
 */
fun unifiedFindRows(hunks: List<Hunk>, maxCharacters: Int): List<FindRow> {
    val rows = ArrayList<FindRow>()

    for (hunk in hunks) {
        rows.add(HEADER_ROW)
        hunk.lines.mapTo(rows) { line ->
            FindRow(listOf(line.shownText(maxCharacters)), line.oldNumber(), line.newNumber())
        }
    }

    return rows
}

/** The rows of a split diff's list: a header row per hunk, then a row per pair of sides, the old one first. */
fun splitFindRows(hunks: List<SplitHunk>, maxCharacters: Int): List<FindRow> {
    val rows = ArrayList<FindRow>()

    for (splitHunk in hunks) {
        rows.add(SPLIT_HEADER_ROW)
        splitHunk.lines.mapTo(rows) { (old, new) ->
            FindRow(
                cells = listOf(old?.shownText(maxCharacters), new?.shownText(maxCharacters)),
                oldLine = old?.displayOldLineNumber,
                newLine = new?.displayNewLineNumber,
            )
        }
    }

    return rows
}

/** Blame's rows: a line of the file each, as blame draws it, numbered from 1. */
fun blameFindRows(lines: List<String>): List<FindRow> = lines.mapIndexed { index, line ->
    FindRow(listOf(line.replaceTabs().removeLineDelimiters()), newLine = index + 1)
}

private val HEADER_ROW = FindRow(listOf(null))
private val SPLIT_HEADER_ROW = FindRow(listOf(null, null))

/**
 * [this] line as the diff pane draws it: the word diff's pieces, or its text, with tabs as four spaces and without its
 * line ending, cut at [maxCharacters], where the pane stops drawing it.
 */
fun Line.shownText(maxCharacters: Int): String {
    val pieces = textDiffed?.diffs?.map { it.text } ?: listOf(text)
    val shown = pieces.joinToString("") { it.replaceTabs().removeLineDelimiters() }

    return if (shown.length > maxCharacters) shown.substring(0, maxCharacters) else shown
}

/**
 * A unified row shows the old number for removed and unchanged lines, and the new one for added and unchanged lines.
 */
private fun Line.oldNumber(): Int? = if (lineType == LineType.ADDED) null else displayOldLineNumber
private fun Line.newNumber(): Int? = if (lineType == LineType.REMOVED) null else displayNewLineNumber

/** Which version's numbers go to line takes: the new one, unless no row has one, as in a deleted file. */
enum class LineSide { OLD, NEW }

fun lineSideOf(rows: List<FindRow>): LineSide =
    if (rows.any { it.newLine != null }) LineSide.NEW else LineSide.OLD

private fun FindRow.lineOn(side: LineSide): Int? = if (side == LineSide.NEW) newLine else oldLine

/** Where go to line takes the list. */
sealed interface LineTarget {
    /** The row that shows the line. */
    data class Row(val row: Int) : LineTarget

    /** No row shows the line, as the hunks leave it out. */
    data object NotShown : LineTarget

    /** Nothing to go to: no row has a number. */
    data object None : LineTarget
}

/**
 * The row of [rows] that shows [line] of the [side] version's [lineCount] lines. A line past either end is the first or
 * the last one, as editors take it.
 */
fun targetOfLine(rows: List<FindRow>, line: Int, lineCount: Int, side: LineSide = lineSideOf(rows)): LineTarget {
    if (rows.none { it.lineOn(side) != null }) return LineTarget.None

    val wanted = line.coerceIn(1, lineCount.coerceAtLeast(1))
    val row = rows.indexOfFirst { it.lineOn(side) == wanted }

    return if (row >= 0) LineTarget.Row(row) else LineTarget.NotShown
}

/** The number typed in the line field: its digits, or null when it has none. Too long a number is the largest. */
fun parseLineNumber(text: String): Int? {
    val digits = text.filter { it in '0'..'9' }
    if (digits.isEmpty()) return null

    return digits.trimStart('0').ifEmpty { "0" }.toIntOrNull() ?: Int.MAX_VALUE
}
