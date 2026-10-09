// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import java.io.ByteArrayOutputStream

private const val NO_NEWLINE_AT_END = "\\ No newline at end of file\n"

/**
 * The patch that applies the [selected] lines of [hunk], for `git apply`, or for `git apply -R` when [reverse] (to
 * unstage or discard them). Returns null when the selection changes nothing.
 *
 * It's the hunk as `git add -p` writes it after the user edits it: applied forward, a removed line that isn't selected
 * stays, as context, and an added line that isn't selected is left out; reversed, the other way round.
 *
 * Each line's bytes come from its side of the diff ([Hunk.oldText], [Hunk.newText]), with its own line ending, and
 * `\ No newline at end of file` after a last line without one. So the patch has exactly the bytes that the diff
 * compared, whatever their encoding. Context comes from the side that the patch applies to: git refuses the patch when
 * that side no longer has the context and the lines that the patch replaces, so a file that changed after the diff was
 * loaded is left as it is. Lines added or removed elsewhere in the file don't stop it, as git finds the hunk where it
 * moved.
 */
internal fun hunkPatch(diffEntry: DiffEntry, hunk: Hunk, reverse: Boolean, selected: (Line) -> Boolean): ByteArray? {
    val lines = patchLines(hunk, reverse, selected)

    if (lines.all { it.kind == PatchLineKind.Context }) {
        return null
    }

    val oldCount = lines.count { it.isInOld }
    val newCount = lines.count { it.isInNew }

    // A patch with the whole of an added or deleted file creates or deletes it, as the diff does. Part of one (a
    // selection reversed, or a deleted file's lines staged) changes the file instead.
    val isNewFile = diffEntry.changeType == DiffEntry.ChangeType.ADD && oldCount == 0
    val isDeletedFile = diffEntry.changeType == DiffEntry.ChangeType.DELETE && newCount == 0

    // Where the hunk starts in the side that the patch applies to, which is where git looks for it first. The other
    // side starts there too, as no hunk comes before this one in the patch.
    val firstLine = hunk.lines.first()
    val start = if (reverse) firstLine.newLineNumber else firstLine.oldLineNumber

    val path = diffEntry.filePath
    val oldName = if (isNewFile) "/dev/null" else patchName("a/", path)
    val newName = if (isDeletedFile) "/dev/null" else patchName("b/", path)

    val header = buildString {
        append("diff --git ${patchName("a/", path)} ${patchName("b/", path)}\n")

        if (isNewFile) {
            append("new file mode ${diffEntry.newMode}\n")
        }

        if (isDeletedFile) {
            append("deleted file mode ${diffEntry.oldMode}\n")
        }

        // A tab after a name with a space, as git writes it, so that a name ending in a space keeps it
        append("--- $oldName${if (' ' in oldName) "\t" else ""}\n")
        append("+++ $newName${if (' ' in newName) "\t" else ""}\n")
        append("@@ -${range(start, oldCount)} +${range(start, newCount)} @@\n")
    }

    return ByteArrayOutputStream().apply {
        writeBytes(header.toByteArray(Charsets.UTF_8))

        for (line in lines) {
            write(line.kind.symbol.code)
            line.text.writeLine(this, line.index)

            if (line.addsLineEnding && line.text.needsCarriageReturn(line.index)) {
                write('\r'.code)
            }

            write('\n'.code)

            if (!line.hasLineEnding) {
                writeBytes(NO_NEWLINE_AT_END.toByteArray(Charsets.UTF_8))
            }
        }
    }.toByteArray()
}

private enum class PatchLineKind(val symbol: Char) {
    Context(' '),
    Removed('-'),
    Added('+'),
}

/**
 * A line of the patch: line [index] of [text]. [addsLineEnding] when it's a last line without one that the patch
 * can't keep last.
 */
private data class PatchLine(
    val kind: PatchLineKind,
    val text: RawText,
    val index: Int,
    val addsLineEnding: Boolean = false,
) {
    val isInOld get() = kind != PatchLineKind.Added
    val isInNew get() = kind != PatchLineKind.Removed

    /** Only the last line of a file can lack a line ending. */
    val hasLineEnding get() = addsLineEnding || index < text.size() - 1 || !text.isMissingNewlineAtEnd

    fun isInSameVersionAs(other: PatchLine) = (isInOld && other.isInOld) || (isInNew && other.isInNew)
}

private fun patchLines(hunk: Hunk, reverse: Boolean, selected: (Line) -> Boolean): List<PatchLine> {
    val lines = hunk.lines.mapNotNull { line ->
        val fromOld = PatchLine(PatchLineKind.Context, hunk.oldText, line.oldLineNumber)
        val fromNew = PatchLine(PatchLineKind.Context, hunk.newText, line.newLineNumber)

        when (line.lineType) {
            LineType.CONTEXT -> if (reverse) fromNew else fromOld

            LineType.REMOVED -> when {
                selected(line) -> fromOld.copy(kind = PatchLineKind.Removed)
                reverse -> null
                else -> fromOld
            }

            LineType.ADDED -> when {
                selected(line) -> fromNew.copy(kind = PatchLineKind.Added)
                reverse -> fromNew
                else -> null
            }
        }
    }

    return lines.withLastLinesKeptLast()
}

/**
 * Keeps each line without a line ending last in the versions of the file that it's in. A selection can put lines after
 * it: added lines after a removed last line that stays, as context, or context after a removed last line that comes
 * back. A context line without one goes after the lines that follow it, which are only added or removed lines, and any
 * other line without one that is followed in its version gets one, as git would when adding a line after it.
 */
private fun List<PatchLine>.withLastLinesKeptLast(): List<PatchLine> {
    val lastContext = firstOrNull { it.kind == PatchLineKind.Context && !it.hasLineEnding }
    val ordered = if (lastContext == null) this else minus(lastContext) + lastContext

    return ordered.mapIndexed { index, line ->
        val isFollowed = !line.hasLineEnding &&
            ordered.subList(index + 1, ordered.size).any { it.isInSameVersionAs(line) }

        if (isFollowed) line.copy(addsLineEnding = true) else line
    }
}

/** Whether a line ending added to line [index] needs `\r`: when the file's lines end with `\r\n`. */
private fun RawText.needsCarriageReturn(index: Int): Boolean {
    val line = getRawString(index)
    val endsWithCarriageReturn = line.hasRemaining() && line.get(line.limit() - 1) == '\r'.code.toByte()

    return lineDelimiter == "\r\n" && !endsWithCarriageReturn
}

/** A side's range in the hunk header, as git writes it: an empty side starts at the line before it. */
private fun range(start: Int, count: Int) = "${if (count == 0) start else start + 1},$count"

/**
 * [prefix] and [path] as a patch names the file: quoted as git quotes it when the path has bytes outside printable
 * ASCII, a quote or a backslash, which `git apply` unquotes.
 */
private fun patchName(prefix: String, path: String): String {
    val name = prefix + path
    val bytes = name.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }

    if (bytes.none { it < 0x20 || it >= 0x7f || it == '"'.code || it == '\\'.code }) {
        return name
    }

    return buildString {
        append('"')

        for (byte in bytes) {
            when {
                byte == '"'.code || byte == '\\'.code -> append('\\').append(byte.toChar())
                byte < 0x20 || byte >= 0x7f -> append('\\').append(byte.toString(8).padStart(3, '0'))
                else -> append(byte.toChar())
            }
        }

        append('"')
    }
}
