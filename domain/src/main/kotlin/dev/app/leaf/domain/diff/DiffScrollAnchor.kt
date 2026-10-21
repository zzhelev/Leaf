// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.diff

import dev.app.leaf.domain.models.DiffResult

/**
 * Where a diff's list is scrolled to, as the line of the file's new version at its top. A diff that ignores whitespace
 * has other rows than the one that shows it, but the lines of the file keep their numbers, so the line at the top of
 * the list stays at the top when the switch is turned.
 */
data class DiffScrollAnchor(val newLine: Int)

/**
 * The new version's line at each row of a diff's list, in order: for each hunk a header row, which has its first
 * line's, then a row for each of its lines (a pair of sides in a split diff, which has the new side's line, or else the
 * old side's, which has the line that comes next in the new version).
 */
private fun DiffResult.TextDiff.rowLines(): IntArray {
    val lines = ArrayList<Int>()

    when (this) {
        is DiffResult.Text -> for (hunk in hunks) {
            lines.add(hunk.lines.firstOrNull()?.newLineNumber ?: 0)
            hunk.lines.mapTo(lines) { it.newLineNumber }
        }

        is DiffResult.TextSplit -> for (splitHunk in hunks) {
            lines.add(splitHunk.sourceHunk.lines.firstOrNull()?.newLineNumber ?: 0)
            splitHunk.lines.mapTo(lines) { (old, new) -> (new ?: old)?.newLineNumber ?: 0 }
        }
    }

    return lines.toIntArray()
}

/** The anchor of [row], or null for a diff with no rows. A row past the end is the last one. */
fun DiffResult.TextDiff.anchorAt(row: Int): DiffScrollAnchor? {
    val lines = rowLines()

    return if (lines.isEmpty()) null else DiffScrollAnchor(lines[row.coerceIn(0, lines.lastIndex)])
}

/**
 * The first row at or after [anchor]'s line, so that a line that this diff hides is replaced by the one that follows
 * it, and the last row when the anchor is past them all.
 */
fun DiffResult.TextDiff.rowAt(anchor: DiffScrollAnchor): Int {
    val lines = rowLines()
    val row = lines.indexOfFirst { it >= anchor.newLine }

    return if (row >= 0) row else (lines.lastIndex).coerceAtLeast(0)
}

/**
 * The row that [after] should be scrolled to for the line at the top of [before], which was scrolled to [row], to stay
 * where it was. Null when either has no text diff to anchor in, and the scroll stays as it is.
 */
fun rowKeptAcross(before: DiffResult?, row: Int, after: DiffResult?): Int? {
    val anchor = (before as? DiffResult.TextDiff)?.anchorAt(row) ?: return null

    return (after as? DiffResult.TextDiff)?.rowAt(anchor)
}
