// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

/*
 * The lines that git and JGit add to MERGE_MSG when a merge, cherry-pick or revert stops at conflicts (fork-only):
 *
 *     Merge branch 'feature'
 *
 *     # Conflicts:
 *     #	file.txt
 *
 * They're comments, in `core.commentChar` (or `core.commentString`), which `git commit` strips when it opens the
 * editor. JGit's commit keeps a message as it is, so a merge committed in Leaf after conflicts kept them. git adds them
 * at the end (`append_conflicts_hint` in sequencer.c), JGit before the message's footers
 * (`MergeMessageFormatter.formatWithConflicts`). With `commit.cleanup=scissors`, git puts its scissors line before
 * them, and `git commit` drops everything from that line on.
 */

private const val CONFLICTS_LINE = " Conflicts:"
private const val SCISSORS_LINE = " ------------------------ >8 ------------------------"

/**
 * [message] without the conflicts that git or JGit listed in it as comments, nor git's scissors line and what follows,
 * so that the commit field shows what its commit will have. The user's own lines stay, even ones that start like a
 * comment, which `git commit` would strip.
 */
fun withoutConflictsHint(message: String): String {
    val lines = message.split("\n")
    val scissors = lines.indexOfFirst { it.commentPrefix(SCISSORS_LINE) != null }
    val kept = if (scissors >= 0) {
        lines.subList(0, scissors).dropBlankLineAtEnd() + ""
    } else {
        withoutConflictsLines(lines)
    }

    return kept.joinToString("\n")
}

/** [lines] without the last block of conflicts in them, and the blank line that separates it. */
private fun withoutConflictsLines(lines: List<String>): List<String> {
    for (start in lines.indices.reversed()) {
        val prefix = lines[start].commentPrefix(CONFLICTS_LINE) ?: continue
        val end = (start + 1..lines.size).first { it == lines.size || !lines[it].startsWith("$prefix\t") }
        val endsBlock = end == lines.size || lines[end].isEmpty()

        if (end > start + 1 && endsBlock && (start == 0 || lines[start - 1].isEmpty())) {
            // The blank line before it, or after it when nothing comes before
            val from = if (start > 0) start - 1 else start
            val to = if (start == 0 && end < lines.size - 1) end + 1 else end

            return lines.subList(0, from) + lines.subList(to, lines.size)
        }
    }

    return lines
}

/** The comment string that starts this line when it's [rest] commented out, as git and JGit write it. */
private fun String.commentPrefix(rest: String): String? {
    val prefix = removeSuffix(rest).takeIf { endsWith(rest) } ?: return null

    // Not a word, so that the user's "Fix Conflicts:" is never taken for one
    return prefix.takeIf { it.isNotEmpty() && it.none { char -> char.isWhitespace() || char.isLetterOrDigit() } }
}

private fun List<String>.dropBlankLineAtEnd() = if (lastOrNull()?.isEmpty() == true) dropLast(1) else this
