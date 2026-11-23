// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editing

import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * The most lines of a file that the whole file's editor takes (fork-only, `docs/fork/inline-editing.md`, B2): a text
 * field lays its whole text out again on each change. Measured on 2026-11-23 (`FileEditorViewTest`, `LEAF_MEASURE`):
 * a keystroke and the frame after it took 31 ms at 1,000 lines, 53 ms at 2,500, 112 ms at 5,000 and 232 ms at 10,000,
 * the median of ten, so about 45 ms here.
 */
const val MAX_FILE_EDIT_LINES = 2_000

/**
 * Editing a working file whole (fork-only, `docs/fork/inline-editing.md`, B2): its [bytes] as they were read (or last
 * saved), which a Save checks the file still has, its lines, and [text], what the field starts with.
 */
class FileEdit(
    /** The file, relative to the working tree. */
    val path: String,
    val bytes: ByteArray,
    /** The file's charset: UTF-8 when it's valid UTF-8, else ISO-8859-1, which keeps every byte. */
    val charset: Charset,
) {
    /** The file's lines, without their line endings. */
    internal val lines: List<ByteArray>

    /** Each line's line ending: `\n`, `\r\n`, or empty for a last line without one. */
    internal val endings: List<String>

    init {
        val parts = splitLines(bytes)
        lines = parts.map { it.first }
        endings = parts.map { it.second }
    }

    /** What the field shows: the lines, joined with `\n`. A file that ends with a line ending has no empty last line. */
    val text: String = lines.joinToString("\n") { charset.decode(ByteBuffer.wrap(it)).toString() }

    /** The file's own line ending, for lines typed: its first line's, or `\n`. */
    val lineEnding: String = endings.firstOrNull { it.isNotEmpty() } ?: LF

    val lineCount: Int get() = lines.size

    /** Whether [typed] can be written in the file's charset. */
    fun canWrite(typed: String): Boolean = charset.newEncoder().canEncode(typed)

    /** Whether [typed] is the file as it is: Save would write nothing. */
    fun isUnchanged(typed: String): Boolean = normalized(typed) == text
}

/**
 * The working file as the whole file's editor reads it: its [bytes], and its version in the index as text
 * ([indexText]), which the markers compare with: empty for a file that the index doesn't have, null when there's
 * nothing to compare with (an LFS pointer in the index).
 */
class FileForEdit(val bytes: ByteArray, val indexText: String?)

/** The edit of the working file whose bytes are [bytes]; null when it has more than [MAX_FILE_EDIT_LINES] lines. */
fun fileEditOf(path: String, bytes: ByteArray): FileEdit? {
    val charset = if (bytes.isUtf8()) Charsets.UTF_8 else Charsets.ISO_8859_1
    val edit = FileEdit(path, bytes, charset)

    return edit.takeIf { it.lineCount <= MAX_FILE_EDIT_LINES }
}

/**
 * The bytes that Save writes for [typed]: a line that the typing didn't change keeps its own bytes and line ending, as
 * a diff of the file's lines against the typed ones finds them, and a line typed takes the file's line ending. The last
 * line has no line ending when the file's last line had none, unless lines were typed after it.
 */
fun savedBytes(edit: FileEdit, typed: String): ByteArray {
    val text = normalized(typed)
    val typedLines = if (text.isEmpty()) emptyList() else text.split('\n')
    val oldRaw = RawText(edit.lines.joinToString("") { "${edit.charset.decode(ByteBuffer.wrap(it))}\n" }.toByteArray())
    val newRaw = RawText(typedLines.joinToString("") { "$it\n" }.toByteArray())
    val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, oldRaw, newRaw)

    // For each typed line, the file's line it is, when the typing left it as it was
    val sameAs = IntArray(typedLines.size) { -1 }
    var oldLine = 0
    var newLine = 0

    for (change in edits + Edit(edit.lineCount, edit.lineCount, typedLines.size, typedLines.size)) {
        while (newLine < change.beginB && oldLine < change.beginA) {
            sameAs[newLine++] = oldLine++
        }
        oldLine = change.endA
        newLine = change.endB
    }

    val fileEndsWithEnding = edit.endings.lastOrNull()?.isNotEmpty() ?: true
    val lastFileLine = edit.lineCount - 1
    val out = ByteArrayOutputStream()

    typedLines.forEachIndexed { index, line ->
        val same = sameAs[index]
        val isLast = index == typedLines.lastIndex

        if (same >= 0) {
            out.writeBytes(edit.lines[same])
        } else {
            out.writeBytes(line.toByteArray(edit.charset))
        }

        val ending = when {
            // The last line ends the file as the file's last line did
            isLast && !fileEndsWithEnding -> ""
            isLast && same == lastFileLine -> edit.endings[same]
            isLast -> edit.lineEnding
            same >= 0 && same != lastFileLine -> edit.endings[same]
            else -> edit.lineEnding
        }

        out.writeBytes(ending.toByteArray(Charsets.ISO_8859_1))
    }

    return out.toByteArray()
}

/** What a marker before the lines of the field says, against the index's version. */
enum class ChangeKind {
    /** Lines that the index doesn't have. */
    ADDED,

    /** Lines in place of others of the index. */
    CHANGED,

    /** Lines of the index that the field doesn't have, between two of its lines. */
    REMOVED,
}

/**
 * A change of the field against the index: the field's lines [start] to [end] (from 0, [end] excluded, empty for
 * [ChangeKind.REMOVED], whose lines went before [start]), and the index's lines in their place, [oldLines].
 */
data class ChangeMarker(val kind: ChangeKind, val start: Int, val end: Int, val oldLines: List<String>)

/**
 * The changes of [typed] against [indexText], the file's version in the index as the Unstaged diff compares: a line
 * diff, as git's (the histogram diff). Null [indexText] (no version to compare) has none.
 */
fun changeMarkers(indexText: String?, typed: String): List<ChangeMarker> {
    if (indexText == null) return emptyList()

    val oldLines = linesOf(indexText)
    val newLines = linesOf(normalized(typed))
    val old = RawText(oldLines.joinToString("") { "$it\n" }.toByteArray())
    val new = RawText(newLines.joinToString("") { "$it\n" }.toByteArray())

    return HistogramDiff().diff(RawTextComparator.DEFAULT, old, new).map { change ->
        val kind = when {
            change.beginA == change.endA -> ChangeKind.ADDED
            change.beginB == change.endB -> ChangeKind.REMOVED
            else -> ChangeKind.CHANGED
        }

        ChangeMarker(kind, change.beginB, change.endB, oldLines.subList(change.beginA, change.endA))
    }
}

/** [typed] with [marker]'s lines as the index has them, in place of the field's: Put it back. */
fun puttingBack(typed: String, marker: ChangeMarker): String {
    val lines = linesOf(normalized(typed)).toMutableList()

    repeat(marker.end - marker.start) { lines.removeAt(marker.start) }
    lines.addAll(marker.start, marker.oldLines)

    return lines.joinToString("\n")
}

/** At most this many occurrences of a name are marked: a common one in a long file would mark everything. */
const val MAX_OCCURRENCES = 2_000

/**
 * The places of the name at [caret] in [text], whole words of letters, digits, `_` and `$`, wherever it is in [text]:
 * where it's used, beyond the lines in view. None when the caret isn't on a name, or the name has one character.
 */
fun occurrencesAt(text: String, caret: Int): List<IntRange> {
    fun isNameChar(char: Char) = char.isLetterOrDigit() || char == '_' || char == '$'

    val at = caret.coerceIn(0, text.length)
    var start = at
    var end = at

    while (start > 0 && isNameChar(text[start - 1])) start--
    while (end < text.length && isNameChar(text[end])) end++

    if (end - start < 2 || text[start].isDigit()) return emptyList()

    val name = text.substring(start, end)
    val found = mutableListOf<IntRange>()
    var index = text.indexOf(name)

    while (index >= 0 && found.size < MAX_OCCURRENCES) {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + name.length)

        if ((before == null || !isNameChar(before)) && (after == null || !isNameChar(after))) {
            found += index until index + name.length
        }

        index = text.indexOf(name, index + name.length)
    }

    return found
}

/** [text]'s lines, split at `\n`: an empty text has none. */
private fun linesOf(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n')

/** [bytes]' lines, each without its line ending, and the line ending, `\n`, `\r\n` or none for a last line without one. */
private fun splitLines(bytes: ByteArray): List<Pair<ByteArray, String>> {
    val lines = mutableListOf<Pair<ByteArray, String>>()
    var start = 0

    for (index in bytes.indices) {
        if (bytes[index] == '\n'.code.toByte()) {
            val crlf = index > start && bytes[index - 1] == '\r'.code.toByte()
            lines += bytes.copyOfRange(start, if (crlf) index - 1 else index) to if (crlf) CRLF else LF
            start = index + 1
        }
    }

    if (start < bytes.size) {
        lines += bytes.copyOfRange(start, bytes.size) to ""
    }

    return lines
}

/** [typed] with its line breaks as `\n`, as pasted text can have `\r\n`. */
private fun normalized(typed: String): String = typed.replace("\r\n", "\n").replace('\r', '\n')
