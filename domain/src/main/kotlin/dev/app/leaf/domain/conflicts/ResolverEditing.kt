// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The most lines of a run of unchanged lines that the resolver edits as one part (`conflict-resolver.md`, section 3).
 * A text field lays its whole text out again on each change, and the part shows as one item of the lists.
 */
const val MAX_PART_LINES = 200

/**
 * The merge with its runs of unchanged lines cut into parts of [maxLines] lines at most. The resolver edits one part of
 * the result at a time, and a part is a chunk.
 */
fun TextMerge.inParts(maxLines: Int = MAX_PART_LINES): TextMerge {
    require(maxLines > 0) { "A part needs a line: $maxLines" }

    // Lines typed in place of a run stay one part: they don't split along the run's lines
    fun cut(chunk: Chunk) = chunk is Chunk.Unchanged && chunk.edited == null && chunk.base.size > maxLines

    if (chunks.none(::cut)) {
        return this
    }

    val parts = chunks.flatMap { chunk ->
        if (!cut(chunk)) {
            listOf(chunk)
        } else {
            (0 until chunk.base.size step maxLines).map { offset ->
                val size = minOf(maxLines, chunk.base.size - offset)

                Chunk.Unchanged(
                    base = chunk.base.part(offset, size),
                    ours = chunk.ours.part(offset, size),
                    theirs = chunk.theirs.part(offset, size),
                )
            }
        }
    }

    return copy(chunks = parts)
}

private fun LineRange.part(offset: Int, size: Int) = LineRange(start + offset, start + offset + size)

/** Whether the user typed [chunk]'s lines in the result. */
fun TextMerge.isEdited(chunk: Int): Boolean = when (val part = chunks[chunk]) {
    is Chunk.Unchanged -> part.edited != null
    is Chunk.Change -> part.edited != null
    is Chunk.Conflict -> part.resolution is ConflictResolution.Edited
}

/** The merge with [lines] typed in place of [chunk]'s lines in the result. A conflict counts as resolved. */
fun TextMerge.typing(chunk: Int, lines: TypedLines): TextMerge = replacing(chunk) { part ->
    when (part) {
        is Chunk.Unchanged -> part.copy(edited = lines)
        is Chunk.Change -> part.copy(edited = lines)
        is Chunk.Conflict -> part.copy(resolution = ConflictResolution.Edited(lines))
    }
}

/**
 * The merge with [chunk]'s lines in the result as the merge made them, in place of lines typed there. A conflict is
 * left as it is: its menu and arrows pick what goes in place of the lines typed.
 */
fun TextMerge.withoutTyping(chunk: Int): TextMerge = replacing(chunk) { part ->
    when (part) {
        is Chunk.Unchanged -> part.copy(edited = null)
        is Chunk.Change -> part.copy(edited = null)
        is Chunk.Conflict -> part
    }
}

private fun TextMerge.replacing(chunk: Int, change: (Chunk) -> Chunk): TextMerge {
    val part = chunks[chunk]
    val changed = change(part)

    return if (changed == part) this else copy(chunks = chunks.toMutableList().apply { set(chunk, changed) })
}

/** The number of [chunk]'s first line in the result, as Save writes it and the columns number it. */
fun TextMerge.firstResultLine(chunk: Int): Int =
    // An open conflict has no lines in the result without Base
    1 + (0 until chunk).sumOf { index -> resultLines(chunks[index], showBase = false).lines.size }

/** The first chunk whose state differs between [this] and [other], two states of the same merge. */
fun TextMerge.firstChangedChunk(other: TextMerge): Int? =
    chunks.indices.firstOrNull { chunks[it] != other.chunks.getOrNull(it) }

/** What editing a part of the result starts from: its [text], and how the lines typed are written ([format]). */
data class EditStart(val text: String, val format: LineFormat)

/**
 * What editing [chunk]'s lines in the result starts from: the lines that the result shows there, or the base's lines
 * for an open conflict, as Meld, IntelliJ and VS Code start a conflict from the base. Lines are joined with `\n`,
 * without their line endings.
 */
fun TextMerge.editStart(chunk: Int): EditStart {
    val lines = editStartLines(chunk)
    val charset = charsetOf(lines.map { (text, line) -> text.lineBytes(line) }) ?: fileCharset()
    val lineEnding = lines.firstNotNullOfOrNull { (text, line) -> text.lineEnding(line) } ?: fileLineEnding()
    // Only the last line of a version can lack a line ending: the part is where the file ends
    val endsWithLineEnding = lines.lastOrNull()?.let { (text, line) -> text.lineEnding(line) != null } ?: true

    return EditStart(
        text = lines.joinToString("\n") { (text, line) -> charset.decode(ByteBuffer.wrap(text.lineBytes(line))).toString() },
        format = LineFormat(charset, lineEnding, endsWithLineEnding),
    )
}

/** The lines that editing [chunk] starts from ([editStart]): each a line of a version, or of lines typed. */
fun TextMerge.editStartLines(chunk: Int): List<Pair<RawText, Int>> {
    val part = chunks[chunk]

    return if (part is Chunk.Conflict && part.resolution == ConflictResolution.Unresolved) {
        lines(base, part.base)
    } else {
        resultLines(part, showBase = false).lines
    }
}

/**
 * How lines typed in place of a part are written: in [charset], each with [lineEnding], except the last when the part
 * is where the file ends without one ([endsWithLineEnding] false).
 */
data class LineFormat(val charset: Charset, val lineEnding: String, val endsWithLineEnding: Boolean) {
    /** Whether [text] can be written in [charset]: a file in ISO-8859-1 has no room for other characters. */
    fun canWrite(text: String): Boolean = charset.newEncoder().canEncode(text)

    /**
     * [text] as lines of the file: each line between `\n`s (or `\r\n`s, as pasted text can have) is a line. An empty
     * text is no line at all, as when the user deleted every line.
     */
    fun typed(text: String): TypedLines {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')

        if (normalized.isEmpty()) {
            return TypedLines(RawText(ByteArray(0)), lineEnding)
        }

        val lines = normalized.split('\n')
        val out = ByteArrayOutputStream()

        lines.forEachIndexed { index, line ->
            out.writeBytes(line.toByteArray(charset))

            if (index < lines.lastIndex || endsWithLineEnding) {
                out.writeBytes(lineEnding.toByteArray(charset))
            }
        }

        return TypedLines(RawText(out.toByteArray()), lineEnding)
    }
}

private const val LF = "\n"
private const val CRLF = "\r\n"

/** Line [index]'s bytes, without its line ending. */
private fun RawText.lineBytes(index: Int): ByteArray {
    // Without the '\n', but with the '\r' of CRLF
    val raw = getRawString(index)
    val bytes = ByteArray(raw.remaining()).also { raw.get(it) }

    return if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte() && lineEnding(index) == CRLF) {
        bytes.copyOf(bytes.size - 1)
    } else {
        bytes
    }
}

/** Line [index]'s line ending, or null for a last line without one. */
private fun RawText.lineEnding(index: Int): String? {
    if (index == size() - 1 && isMissingNewlineAtEnd) {
        return null
    }

    val raw = getRawString(index)

    return if (raw.hasRemaining() && raw.get(raw.limit() - 1) == '\r'.code.toByte()) CRLF else LF
}

/**
 * The charset that decodes every line of [lines] as RawText shows them: UTF-8 when they are valid UTF-8, else
 * ISO-8859-1, which JGit falls back to and which decodes and encodes any byte as it is. Null without lines.
 */
private fun charsetOf(lines: List<ByteArray>): Charset? = when {
    lines.isEmpty() -> null
    lines.all { it.isUtf8() } -> Charsets.UTF_8
    else -> Charsets.ISO_8859_1
}

/** The charset of the file, for a part without lines: see [charsetOf]. */
private fun TextMerge.fileCharset(): Charset =
    if (listOf(base, ours, theirs).all { it.rawContent.isUtf8() }) Charsets.UTF_8 else Charsets.ISO_8859_1

/** The line ending of the file, for a part without lines: the first line ending of ours, the base or theirs. */
private fun TextMerge.fileLineEnding(): String =
    listOf(ours, base, theirs).firstNotNullOfOrNull { text -> (0 until text.size()).firstNotNullOfOrNull(text::lineEnding) }
        ?: LF

private fun ByteArray.isUtf8(): Boolean = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
    true
} catch (_: CharacterCodingException) {
    false
}
