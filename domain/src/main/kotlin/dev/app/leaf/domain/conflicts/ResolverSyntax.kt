// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.extensions.lineAt
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.domain.highlighting.highlightedTextOf
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines

/**
 * The text of a part being edited and its colours (fork-only, `docs/fork/syntax-highlighting.md`, step 6): line `i` of
 * [text] has the spans of line `i` of [highlighted]. The field may have changed since: what it shows is matched line by
 * line.
 */
class EditorSyntax(val text: String, val highlighted: HighlightedText)

/**
 * What editing [chunk] starts with, coloured as the rows show its lines ([versions]), so that the field doesn't go
 * plain when editing starts.
 */
fun TextMerge.editStartSyntax(chunk: Int, versions: HighlightedVersions): EditorSyntax = EditorSyntax(
    text = editStart(chunk).text,
    highlighted = highlightedTextOf(editStartLines(chunk).map { (text, line) -> versions.spansOf(text, line) }),
)

/**
 * The lines that come before [chunk] in ours, each with its line ending: lines typed in its place are coloured after
 * them, so that a part that starts in a comment or a string is coloured as one. Ours is the file that the merge started
 * from; the result has the same lines there, unless a pick above changed them.
 */
fun TextMerge.textBefore(chunk: Int): String {
    val end = chunks[chunk].ours.start
    val builder = StringBuilder()

    for (line in 0 until end) {
        builder.append(ours.lineAt(line))
    }

    return builder.toString()
}

/** The lines typed in this merge, with the chunk that each takes the place of. */
fun TextMerge.typedLines(): List<Pair<Int, TypedLines>> = chunks.mapIndexedNotNull { index, chunk ->
    val typed = when (chunk) {
        is Chunk.Unchanged -> chunk.edited
        is Chunk.Change -> chunk.edited
        is Chunk.Conflict -> (chunk.resolution as? ConflictResolution.Edited)?.lines
    }

    typed?.let { index to it }
}

/**
 * The spans of each of [lines], the field's text now, from this syntax, which may be of an earlier text: the lines
 * before the first one that changed, and after the last one, keep their spans, and the first changed line keeps those
 * of the characters before the change. The others are plain until the field's text is coloured again.
 */
fun EditorSyntax.spansFor(lines: List<String>): List<List<SyntaxSpan>> {
    val old = text.split('\n')
    val same = minOf(lines.size, old.size)
    var before = 0
    while (before < same && lines[before] == old[before]) before++
    var after = 0
    while (after < same - before && lines[lines.size - 1 - after] == old[old.size - 1 - after]) after++

    return lines.indices.map { index ->
        when {
            index < before -> highlighted.spansOf(index)
            index >= lines.size - after -> highlighted.spansOf(index - lines.size + old.size)
            index == before && before < old.size -> {
                val kept = lines[index].commonPrefixWith(old[index]).length
                highlighted.spansOf(index).filter { it.start < kept }.map { it.copy(end = minOf(it.end, kept)) }
            }

            else -> emptyList()
        }
    }
}
