// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.app.leaf.domain.DiffMatchPatch
import dev.app.leaf.domain.conflicts.CellKind
import dev.app.leaf.domain.conflicts.ChangedWords
import dev.app.leaf.domain.conflicts.ColumnCell
import dev.app.leaf.domain.models.MatchLine
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.conflictFile

/**
 * The marks of [cell]'s line, the [words] that its side changed (`conflict-resolver.md`, section 11), and their colour:
 * a stronger shade of the cell's own. None for a filler, the base's lines and lines typed.
 */
@Composable
internal fun marksOf(cell: ColumnCell, words: ChangedWords): LineMarks? {
    val marks = words.of(cell.text, cell.line)

    return if (marks.isEmpty()) null else LineMarks(marks, cell.kind.markColor())
}

/** Ranges of a line's characters to mark, as `RawText.getString` gives the line, and their background. */
internal data class LineMarks(val ranges: List<IntRange>, val color: Color)

/** [text] as [DiffText]'s line: the marks inserted, the rest equal. */
internal fun LineMarks.matchLine(text: String): MatchLine {
    val diffs = mutableListOf<DiffMatchPatch.Diff>()
    var start = 0

    for (range in ranges) {
        val from = range.first.coerceIn(start, text.length)
        val to = (range.last + 1).coerceIn(from, text.length)

        if (from > start) diffs += DiffMatchPatch.Diff(DiffMatchPatch.Operation.EQUAL, text.substring(start, from))
        if (to > from) diffs += DiffMatchPatch.Diff(DiffMatchPatch.Operation.INSERT, text.substring(from, to))
        start = to
    }

    if (start < text.length) diffs += DiffMatchPatch.Diff(DiffMatchPatch.Operation.EQUAL, text.substring(start))

    return MatchLine(diffs)
}

/** A stronger shade of [background]'s colour, for the words marked inside a line of this kind. */
@Composable
internal fun CellKind.markColor(): Color = when (this) {
    CellKind.CHANGED_BY_OURS -> MaterialTheme.colors.primary.copy(alpha = 0.5f)
    CellKind.CHANGED_BY_THEIRS -> TheirsColor.copy(alpha = 0.5f)
    CellKind.CHANGED_BY_BOTH -> MaterialTheme.colors.onBackground.copy(alpha = 0.2f)
    CellKind.CONFLICT, CellKind.OPEN_CONFLICT -> MaterialTheme.colors.conflictFile.copy(alpha = 0.45f)
    CellKind.RESOLVED -> MaterialTheme.colors.addFile.copy(alpha = 0.45f)
    CellKind.UNCHANGED, CellKind.EDITED -> Color.Transparent
}
