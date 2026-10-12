// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import org.eclipse.jgit.diff.RawText

/**
 * The conflicts that a file's markers make: [conflicts] complete ones, outside other conflicts. [pairsUp] is false when
 * a marker comes out of order or a conflict isn't closed, where `git rerere` gives up on the file; [conflicts] then
 * counts the ones before.
 */
internal data class ConflictMarkerScan(val conflicts: Int, val pairsUp: Boolean)

private enum class ConflictPart {
    OURS,
    BASE,
    THEIRS,
}

/**
 * Finds the conflict markers in [text] as `git rerere` does (`handle_path` and `handle_conflict` in rerere.c), with
 * markers of [markerSize] characters (the `conflict-marker-size` attribute):
 * - a marker is at the start of a line: `<<<<<<<` and `>>>>>>>` followed by a space (git always labels them),
 *   `|||||||` and `=======` followed by a space or the line's end;
 * - a conflict is `<<<<<<<`, an optional `|||||||`, `=======` and `>>>>>>>`, in that order. Conflicts can nest inside
 *   ours or theirs, as when a file that had markers was merged again;
 * - lines that look like `=======` or `>>>>>>>` outside a conflict are text.
 */
internal fun scanConflictMarkers(text: RawText, markerSize: Int): ConflictMarkerScan {
    // The part of each conflict that the scan is in, the innermost last
    val open = ArrayDeque<ConflictPart>()
    var conflicts = 0

    fun outOfOrder() = ConflictMarkerScan(conflicts, pairsUp = false)

    for (index in 0 until text.size()) {
        val marker = text.markerAt(index, markerSize)

        if (open.isEmpty()) {
            if (marker == '<') {
                open.addLast(ConflictPart.OURS)
            }

            continue
        }

        when (marker) {
            '<' -> open.addLast(ConflictPart.OURS)
            '|' -> if (open.last() == ConflictPart.OURS) {
                open[open.lastIndex] = ConflictPart.BASE
            } else {
                return outOfOrder()
            }

            '=' -> if (open.last() != ConflictPart.THEIRS) {
                open[open.lastIndex] = ConflictPart.THEIRS
            } else {
                return outOfOrder()
            }

            '>' -> if (open.last() == ConflictPart.THEIRS) {
                open.removeLast()

                if (open.isEmpty()) {
                    conflicts++
                }
            } else {
                return outOfOrder()
            }
        }
    }

    return if (open.isEmpty()) ConflictMarkerScan(conflicts, pairsUp = true) else outOfOrder()
}

/** The marker that line [index] starts with (`<`, `|`, `=` or `>`), or null, as rerere's `is_cmarker` tells it. */
private fun RawText.markerAt(index: Int, markerSize: Int): Char? {
    // Without the line's '\n'
    val line = getRawString(index)

    if (line.remaining() < markerSize) {
        return null
    }

    val character = line.get(line.position()).toInt().toChar()

    if (character !in "<|=>") {
        return null
    }

    for (offset in 0 until markerSize) {
        if (line.get(line.position() + offset).toInt().toChar() != character) {
            return null
        }
    }

    val next = if (line.remaining() > markerSize) {
        line.get(line.position() + markerSize).toInt().toChar()
    } else if (index < size() - 1 || !isMissingNewlineAtEnd) {
        '\n'
    } else {
        // The file's end: rerere sees the string's terminating NUL, which isn't a space
        return null
    }

    val labelled = character == '<' || character == '>'

    return when {
        labelled && next != ' ' -> null
        next in " \t\n\r\u000B\u000C" -> character
        else -> null
    }
}
