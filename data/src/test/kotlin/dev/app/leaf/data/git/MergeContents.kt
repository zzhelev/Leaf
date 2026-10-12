// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import java.io.File

/**
 * A file's versions for a merge: its [base], [ours] and [theirs], and what merging them gives, as [conflictIn] shows
 * it. The tests of `merge.conflictStyle` and `diff.algorithm` merge them in [FILE].
 */
internal sealed class MergeContents(val base: String, val ours: String, val theirs: String, val result: String) {
    /**
     * A conflict where both sides added X before and Z after their own line, which zdiff3 leaves outside of it, and
     * diff3 inside.
     */
    data object Conflict : MergeContents(
        base = lines("1", "2", "a", "b", "c", "3", "4"),
        ours = lines("1", "2", "a", "X", "Y", "Z", "c", "3", "4"),
        theirs = lines("1", "2", "a", "X", "Q", "Z", "c", "3", "4"),
        result = lines("1", "2", "a", "X", "<<<<<<<", "Y", "|||||||", "=======", "Q", ">>>>>>>", "Z", "c", "3", "4"),
    )

    /** Changes to lines far apart, which merge without conflicts. */
    data object Clean : MergeContents(
        base = lines("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
        ours = lines("1", "two", "3", "4", "5", "6", "7", "8", "9", "10"),
        theirs = lines("1", "2", "3", "4", "5", "6", "7", "8", "nine", "10"),
        result = lines("1", "two", "3", "4", "5", "6", "7", "8", "nine", "10"),
    )

    /**
     * Changes that myers merges without conflicts and histogram doesn't, in JGit and in git (`git merge-file`), found
     * by trying random ones. git's `minimal` merges them as myers, and `patience` as histogram.
     */
    data object MyersOnlyMerge : MergeContents(
        base = lines("{", "", "x", "a", "{", "}", "x", "{", "{", "}", "x"),
        ours = lines("{", "", "}", "x", "", "{", "}", "x", "{", "{", "}", "a"),
        theirs = lines("{", "", "x", "a", "{", "}", "x", "{", "{", "x", "}", "x"),
        result = lines("{", "", "}", "x", "", "{", "}", "x", "{", "{", "x", "}", "a"),
    )

    companion object {
        const val FILE = "file.txt"
        const val ZDIFF3 = "merge.conflictStyle=zdiff3"

        /**
         * [FILE] in [repository], with the labels of conflict markers left out, and the lines of a conflict's base:
         * JGit's diff3 leaves out as many lines from the base as it leaves out of the sides, where git shows them all
         * (the base's `b` in [Conflict]).
         */
        fun conflictIn(repository: File): String {
            var inBase = false

            return File(repository, FILE).readLines().mapNotNull { line ->
                val marker = line.take(7)

                when {
                    marker == "|||||||" -> marker.also { inBase = true }
                    marker == "=======" -> marker.also { inBase = false }
                    inBase -> null
                    marker == "<<<<<<<" || marker == ">>>>>>>" -> marker
                    else -> line
                }
            }.joinToString("") { "$it\n" }
        }
    }
}

private fun lines(vararg lines: String) = lines.joinToString("") { "$it\n" }
