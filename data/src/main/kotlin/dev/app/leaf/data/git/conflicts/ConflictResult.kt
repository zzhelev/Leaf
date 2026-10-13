// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.Config
import java.io.ByteArrayOutputStream

/** git's length of conflict markers, unless the `conflict-marker-size` attribute sets another. */
internal const val DEFAULT_CONFLICT_MARKER_SIZE = 7

private const val LF = "\n"
private const val CRLF = "\r\n"

/**
 * How the conflicts still open are written into the file: with the base's lines when `merge.conflictStyle` asks for
 * them ([withBase]), with markers of [size] characters, and [labels] after them.
 */
internal data class ConflictMarkerStyle(
    val withBase: Boolean,
    val labels: ConflictLabels,
    val size: Int = DEFAULT_CONFLICT_MARKER_SIZE,
)

/** What follows each marker, as git names the versions: `HEAD`, the branch merged, `a1b2c3d (Fix parser)`. */
internal data class ConflictLabels(val ours: String, val base: String, val theirs: String)

/**
 * Whether `merge.conflictStyle` asks for the base's lines in conflicts. `zdiff3` is written as `diff3`: JGit's merge
 * already moves the lines that both sides have alike out of the conflict, which is what `zdiff3` adds.
 */
internal fun conflictStyleWithBase(config: Config): Boolean =
    config.getString("merge", null, "conflictStyle")?.lowercase() in setOf("diff3", "zdiff3")

/**
 * The file that the merge's chunks make, as git would write it, with the lines that the user typed in place of the
 * parts they typed them in:
 * - each line is written with the bytes it has in its version, line ending included, so an encoding other than UTF-8
 *   and CRLF line endings stay as they were;
 * - a line without a line ending (the last line of its version) gets one when something follows it, as when a
 *   conflict takes both sides, or is written with markers;
 * - conflicts still open are written with markers, which end with CRLF when the lines around them do. That follows
 *   git (`is_cr_needed` in xdiff): it looks at ours' and theirs' lines just before the conflict and the base's first
 *   line, so a file that both sides added gets LF. JGit's merges write markers with LF.
 */
internal fun TextMerge.resultBytes(style: ConflictMarkerStyle): ByteArray {
    val writer = ResultWriter()

    for (chunk in chunks) {
        when (chunk) {
            is Chunk.Unchanged -> when (val edited = chunk.edited) {
                null -> writer.lines(base, chunk.base)
                else -> writer.lines(edited)
            }

            is Chunk.Change -> {
                val edited = chunk.edited

                when {
                    edited != null -> writer.lines(edited)
                    !chunk.applied -> writer.lines(base, chunk.base)
                    chunk.by == ChangedBy.THEIRS -> writer.lines(theirs, chunk.theirs)
                    else -> writer.lines(ours, chunk.ours)
                }
            }

            is Chunk.Conflict -> writeConflict(writer, chunk, style)
        }
    }

    return writer.bytes()
}

private fun TextMerge.writeConflict(writer: ResultWriter, conflict: Chunk.Conflict, style: ConflictMarkerStyle) {
    val lineEnding = markerLineEnding(conflict)

    fun ours() = writer.lines(ours, conflict.ours, lineEnding)
    fun theirs() = writer.lines(theirs, conflict.theirs, lineEnding)

    when (val resolution = conflict.resolution) {
        ConflictResolution.Unresolved -> {
            writer.marker(marker('<', style.size, style.labels.ours), lineEnding)
            ours()

            if (style.withBase) {
                writer.marker(marker('|', style.size, style.labels.base), lineEnding)
                writer.lines(base, conflict.base, lineEnding)
            }

            writer.marker(marker('=', style.size, label = ""), lineEnding)
            theirs()
            writer.marker(marker('>', style.size, style.labels.theirs), lineEnding)
        }

        ConflictResolution.Ours -> ours()
        ConflictResolution.Theirs -> theirs()
        ConflictResolution.OursThenTheirs -> {
            ours()
            theirs()
        }

        ConflictResolution.TheirsThenOurs -> {
            theirs()
            ours()
        }

        ConflictResolution.Base -> writer.lines(base, conflict.base, lineEnding)
        is ConflictResolution.Edited -> writer.lines(resolution.lines)
    }
}

private fun marker(character: Char, size: Int, label: String): String {
    val marker = character.toString().repeat(size)

    return if (label.isEmpty()) marker else "$marker $label"
}

/** The line ending of [conflict]'s markers, as git picks it (`is_cr_needed` in xdiff/xmerge.c). */
private fun TextMerge.markerLineEnding(conflict: Chunk.Conflict): String {
    var crlf = ours.lineEndsWithCrlf(lineBefore(conflict.ours))

    if (crlf != 0) {
        crlf = theirs.lineEndsWithCrlf(lineBefore(conflict.theirs))
    }

    if (crlf != 0) {
        crlf = base.lineEndsWithCrlf(0)
    }

    return if (crlf > 0) CRLF else LF
}

private fun lineBefore(range: LineRange) = if (range.start > 0) range.start - 1 else 0

/**
 * Whether line [index] ends with CRLF (1) or LF (0), or -1 when that can't be told, as git's `is_eol_crlf` tells it: a
 * last line without a line ending is told by the line before it, and an empty file or a single line without one can't
 * be told.
 */
private fun RawText.lineEndsWithCrlf(index: Int): Int = when {
    size() == 0 -> -1
    index < size() - 1 || !isMissingNewlineAtEnd -> if (endsWithCarriageReturn(index)) 1 else 0
    index == 0 -> -1
    else -> if (endsWithCarriageReturn(index - 1)) 1 else 0
}

/** Whether line [index] ends with `\r`, before its `\n`. */
private fun RawText.endsWithCarriageReturn(index: Int): Boolean {
    val line = getRawString(index)

    return line.hasRemaining() && line.get(line.limit() - 1) == '\r'.code.toByte()
}

/** Only the last line of a text can lack a line ending. */
private fun RawText.hasLineEnding(index: Int) = index < size() - 1 || !isMissingNewlineAtEnd

/** The line ending of a line that has one in [text], as git tells it: CRLF only when the file uses it there. */
private fun RawText.lineEndingAt(index: Int) = if (lineEndsWithCrlf(index) > 0) CRLF else LF

private class ResultWriter {
    private val out = ByteArrayOutputStream()

    /** The line ending that the last line written lacks, written as soon as anything follows it. */
    private var owedLineEnding: String? = null

    /**
     * Writes [range] of [text]. A last line without a line ending gets [lineEnding] if something follows it, or else
     * one like its own text's.
     */
    fun lines(text: RawText, range: LineRange, lineEnding: String? = null) {
        for (index in range.start until range.end) {
            payOwedLineEnding()
            // Without the '\n', but with the '\r' of CRLF
            text.writeLine(out, index)

            if (text.hasLineEnding(index)) {
                out.write('\n'.code)
            } else {
                owedLineEnding = lineEnding ?: text.lineEndingAt(index)
            }
        }
    }

    /** Writes lines that the user typed, which have the file's line endings already. */
    fun lines(typed: TypedLines) = lines(typed.text, LineRange(0, typed.size), typed.lineEnding)

    fun marker(marker: String, lineEnding: String) {
        payOwedLineEnding()
        out.writeBytes((marker + lineEnding).toByteArray(Charsets.UTF_8))
    }

    /** The file. It ends without a line ending when its last line has none. */
    fun bytes(): ByteArray = out.toByteArray()

    private fun payOwedLineEnding() {
        owedLineEnding?.let { out.writeBytes(it.toByteArray(Charsets.UTF_8)) }
        owedLineEnding = null
    }
}
