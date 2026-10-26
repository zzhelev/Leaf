// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

import dev.app.leaf.domain.extensions.lineAt
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import org.eclipse.jgit.diff.RawText

/**
 * The colours of the two versions that a diff shows (`docs/fork/syntax-highlighting.md`, 4.3), each null while it's
 * coloured, or when nothing colours it.
 */
class DiffHighlights(
    val old: HighlightedText?,
    val new: HighlightedText?,
    /** The diff ignored whitespace, so its context lines show the new version's text (`FormatHunksGitAction`). */
    private val contextLinesAreNew: Boolean = false,
) {
    /**
     * The spans of [line]: a removed line's from the old version, an added line's from the new one. A context line
     * takes the new version's, as the file is now, but on the old side of a split diff ([onOldSide]) the old version's,
     * which differ where a change above it opens or closes a comment or a string, unless its text is the new one's.
     */
    fun spansOf(line: Line, onOldSide: Boolean = false): List<SyntaxSpan> {
        val fromOld = when (line.lineType) {
            LineType.REMOVED -> true
            LineType.ADDED -> false
            LineType.CONTEXT -> onOldSide && !contextLinesAreNew
        }

        return if (fromOld) old?.spansOf(line.oldLineNumber).orEmpty() else new?.spansOf(line.newLineNumber).orEmpty()
    }
}

/**
 * Versions of files and their colours, found by the [RawText] that a diff's hunks hold: each load of a diff makes new
 * ones, so a version's colours belong to its diff, never to another that the view may still draw.
 */
class HighlightedVersions(val versions: List<Version> = emptyList()) {
    /** A version, read from [path], and its colours, null while it's coloured or when nothing colours it. */
    class Version(val text: RawText, val path: String, val highlighted: HighlightedText?)

    /** The version that is [text], the instance. */
    fun versionOf(text: RawText): Version? = versions.firstOrNull { it.text === text }

    /** The colours of [diff]'s versions, or null when it has no lines or neither is coloured. */
    fun forDiff(diff: DiffResult): DiffHighlights? {
        val hunk = diff.firstHunk() ?: return null
        val old = versionOf(hunk.oldText)?.highlighted
        val new = versionOf(hunk.newText)?.highlighted

        return if (old == null && new == null) null else DiffHighlights(old, new, hunk.ignoresWhitespace)
    }

    /** These versions, [text]'s with [highlighted] as its colours. */
    fun withHighlighted(text: RawText, highlighted: HighlightedText): HighlightedVersions =
        HighlightedVersions(versions.map { if (it.text === text) Version(it.text, it.path, highlighted) else it })
}

/**
 * The first hunk of a text diff, whose old and new texts all its hunks share, or null for a diff without lines (a
 * binary file, a rename without changes).
 */
fun DiffResult.firstHunk(): Hunk? = when (this) {
    is DiffResult.Text -> hunks.firstOrNull()
    is DiffResult.TextSplit -> hunks.firstOrNull()?.sourceHunk
    else -> null
}

/** The versions that [this] diff shows, the old and the new, with the paths they're read from. Empty without lines. */
fun DiffResult.versions(): List<Pair<RawText, String>> {
    val hunk = firstHunk() ?: return emptyList()

    return listOf(hunk.oldText to diffEntry.oldPath, hunk.newText to diffEntry.newPath)
}

/**
 * [this] text whole, each line decoded on its own as the diff decodes it ([lineAt]), so that a line's spans fit the
 * line that the diff shows: decoding the whole at once falls back to another charset for every line when one isn't
 * valid UTF-8.
 */
fun RawText.decodedText(): String {
    val builder = StringBuilder(rawContent.size)

    for (line in 0 until size()) {
        builder.append(lineAt(line))
    }

    return builder.toString()
}
