package dev.app.leaf.domain.models

import org.eclipse.jgit.diff.RawText

/**
 * @param oldText the old side of the diff that the hunk is part of, and [newText] its new side. The hunk and line
 * actions take the bytes of their patches from them: [Line.text] is decoded, and can't give the bytes back.
 */
data class Hunk(
    val header: String,
    val lines: List<Line>,
    val oldText: RawText,
    val newText: RawText,
)

data class SplitHunk(val sourceHunk: Hunk, val lines: List<Pair<Line?, Line?>>)

data class Line(
    val text: String,
    val oldLineNumber: Int,
    val newLineNumber: Int,
    val lineType: LineType,
    val textDiffed: MatchLine? = null,
) {
    // lines numbers are stored based on 0 being the first one but on a file the first line is the 1, so increment it!
    val displayOldLineNumber: Int = oldLineNumber + 1
    val displayNewLineNumber: Int = newLineNumber + 1
}

enum class LineType {
    CONTEXT,
    ADDED,
    REMOVED,
}
