// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editing

import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.LineType
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * The most lines of a hunk that can be edited in place (fork-only, `docs/fork/inline-editing.md`): a text field lays its
 * whole text out again on each change, and the view of the complete file is one hunk.
 */
const val MAX_HUNK_EDIT_LINES = 1_000

/**
 * Editing a hunk of an Unstaged diff in place (fork-only, `docs/fork/inline-editing.md`): its new side, the working
 * file's lines from [start] (from 0) as the diff read them ([lines]), which [text] starts from.
 */
class HunkEdit(
    /** The file, relative to the working tree. */
    val path: String,
    val start: Int,
    /** The new side's lines, without their line endings, and without a `\r` before one. */
    val lines: List<ByteArray>,
    /** The old side's lines, the context and the removed ones, which tell the lines that typing changes. */
    val oldLines: List<String>,
    /** The lines' charset: UTF-8 when they're valid UTF-8, else ISO-8859-1, which keeps every byte. */
    val charset: Charset,
) {
    /** What the field starts with: the lines, joined with `\n`. */
    val text: String = lines.joinToString("\n") { charset.decode(ByteBuffer.wrap(it)).toString() }

    /** Whether [typed] can be written in the lines' charset: a file in ISO-8859-1 has no room for other characters. */
    fun canWrite(typed: String): Boolean = charset.newEncoder().canEncode(typed)

    /** Whether [typed] leaves the lines as they were. */
    fun isUnchanged(typed: String): Boolean = normalized(typed) == text
}

/** The edit of [hunk]'s new side, in the file at [path]; null for a hunk with more than [MAX_HUNK_EDIT_LINES] lines. */
fun hunkEditOf(path: String, hunk: Hunk): HunkEdit? {
    // A removed line's new number is where it was: the new side starts at the first line's, whatever it is
    val start = hunk.lines.firstOrNull()?.newLineNumber ?: return null
    val count = hunk.lines.count { it.lineType != LineType.REMOVED }

    if (count > MAX_HUNK_EDIT_LINES) return null

    // Without the line ending: a `\r` before the `\n` goes too, and a last line's own `\r` stays, as the file has it
    val lines = (start until start + count).map { hunk.newText.lineBytes(it) }

    return HunkEdit(
        path = path,
        start = start,
        lines = lines,
        oldLines = hunk.lines.filter { it.lineType != LineType.ADDED }.map { it.text.removeSuffix("\r") },
        charset = charsetOf(lines) ?: if (hunk.newText.rawContent.isUtf8()) Charsets.UTF_8 else Charsets.ISO_8859_1,
    )
}

/** The file with an edit's lines typed in: its [bytes], and the typed lines, from [firstLine] (from 1), [lineCount] of them. */
class EditedFile(val bytes: ByteArray, val firstLine: Int, val lineCount: Int)

/**
 * The working file [file] with [edit]'s lines replaced by [typed], or null when the file doesn't have them where the
 * diff showed them any more: it changed after the diff was read. Lines compare without a `\r` before the `\n`, as
 * `core.autocrlf` gives the diff `LF` where the file has `CRLF`. The typed lines are written in the lines' charset, each
 * with the line ending that the replaced lines had, and the last without one where the file ended without one.
 */
fun editedFile(file: ByteArray, edit: HunkEdit, typed: String): EditedFile? {
    val lines = fileLines(file)
    val end = edit.start + edit.lines.size

    if (end > lines.size) return null
    if (edit.lines.indices.any { !lines[edit.start + it].content.contentEquals(edit.lines[it]) }) return null

    val replaced = lines.subList(edit.start, end)
    val lineEnding = replaced.firstNotNullOfOrNull { it.ending.ifEmpty { null } }
        ?: lines.getOrNull(edit.start - 1)?.ending?.ifEmpty { null }
        ?: lines.firstNotNullOfOrNull { it.ending.ifEmpty { null } }
        ?: LF
    val reachesEnd = end == lines.size
    val fileEndsWithLineEnding = lines.lastOrNull()?.ending?.isNotEmpty() ?: true
    val typedLines = LineFormat(
        charset = edit.charset,
        lineEnding = lineEnding,
        endsWithLineEnding = !reachesEnd || fileEndsWithLineEnding,
    ).typed(normalized(typed))

    val out = ByteArrayOutputStream()

    lines.subList(0, edit.start).forEachIndexed { index, line ->
        out.writeBytes(line.content)

        // Lines typed after a last line without a line ending: that line gets one, and the typed lines end the file
        val needsEnding = index == edit.start - 1 && reachesEnd && line.ending.isEmpty() && typedLines.size > 0
        out.writeBytes(if (needsEnding) lineEnding.toByteArray(edit.charset) else line.ending.toByteArray(Charsets.ISO_8859_1))
    }

    out.writeBytes(typedLines.text.rawContent)

    lines.subList(end, lines.size).forEach { line ->
        out.writeBytes(line.content)
        out.writeBytes(line.ending.toByteArray(Charsets.ISO_8859_1))
    }

    return EditedFile(out.toByteArray(), firstLine = edit.start + 1, lineCount = typedLines.size)
}

/**
 * The typed lines (from 0) that differ from the old side of the hunk, which the field marks: the lines that a diff of
 * the old side against [typed] gives as added.
 */
fun changedTypedLines(edit: HunkEdit, typed: String): Set<Int> {
    val text = normalized(typed)
    val old = RawText(edit.oldLines.joinToString("") { "$it\n" }.toByteArray())
    val new = RawText(if (text.isEmpty()) ByteArray(0) else "$text\n".toByteArray())

    return HistogramDiff().diff(RawTextComparator.DEFAULT, old, new)
        .flatMap { it.beginB until it.endB }
        .toSet()
}

/** A line of a file: its bytes, and its line ending, `\n`, `\r\n` or none for a last line without one. */
private class FileLine(val content: ByteArray, val ending: String)

/** [file]'s lines, split at each `\n` as git and RawText split them. */
private fun fileLines(file: ByteArray): List<FileLine> {
    val lines = mutableListOf<FileLine>()
    var start = 0

    for (index in file.indices) {
        if (file[index] == '\n'.code.toByte()) {
            val crlf = index > start && file[index - 1] == '\r'.code.toByte()
            val contentEnd = if (crlf) index - 1 else index

            lines += FileLine(file.copyOfRange(start, contentEnd), if (crlf) CRLF else LF)
            start = index + 1
        }
    }

    if (start < file.size) {
        lines += FileLine(file.copyOfRange(start, file.size), "")
    }

    return lines
}

/** [typed] with its line breaks as `\n`, as pasted text can have `\r\n`. */
private fun normalized(typed: String): String = typed.replace("\r\n", "\n").replace('\r', '\n')
