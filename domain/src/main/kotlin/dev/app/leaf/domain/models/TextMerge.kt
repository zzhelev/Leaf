// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.eclipse.jgit.diff.RawText

/** Lines [start] (counted from 0) to [end], excluded, of one version of a file. */
data class LineRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "Not a line range: [$start, $end)" }
    }

    val size get() = end - start

    val isEmpty get() = start == end
}

/**
 * The three versions of a text file with conflicts, merged as Leaf's merges merge them (JGit's `MergeAlgorithm`), in
 * chunks (`docs/fork/conflict-resolver.md`, 2.1). Every line of each version is in exactly one chunk, and the chunks
 * are in the order of the file, so the resolver's columns line up chunk by chunk.
 *
 * The texts keep the file's bytes: the result is written from them, never from decoded text. [base] is empty when
 * both sides added the file.
 */
data class TextMerge(
    val base: RawText,
    val ours: RawText,
    val theirs: RawText,
    val chunks: List<Chunk>,
) {
    /** The conflicts that no resolution was picked for yet. Mark resolved needs none. */
    val openConflicts: Int
        get() = chunks.count { it is Chunk.Conflict && it.resolution == ConflictResolution.Unresolved }

    val conflicts: Int get() = chunks.count { it is Chunk.Conflict }

    /** Where a chunk's lines are in each version. Any of them can be empty. */
    sealed interface Chunk {
        val base: LineRange
        val ours: LineRange
        val theirs: LineRange

        /** Lines that are the same in the three versions. */
        data class Unchanged(
            override val base: LineRange,
            override val ours: LineRange,
            override val theirs: LineRange,
        ) : Chunk

        /**
         * A change that doesn't conflict: [by] one side, or by both the same way. The result has the change while it's
         * [applied], and the base's lines otherwise. The side that didn't make it has the base's lines.
         */
        data class Change(
            override val base: LineRange,
            override val ours: LineRange,
            override val theirs: LineRange,
            val by: ChangedBy,
            val applied: Boolean = true,
        ) : Chunk

        /**
         * Lines that both sides changed in different ways. The base's lines are the ones that JGit's merge shows with
         * them in `diff3` style.
         */
        data class Conflict(
            override val base: LineRange,
            override val ours: LineRange,
            override val theirs: LineRange,
            val resolution: ConflictResolution = ConflictResolution.Unresolved,
        ) : Chunk
    }
}

/** Who made a change that doesn't conflict. */
enum class ChangedBy {
    OURS,
    THEIRS,

    /** Both sides, the same way. */
    BOTH,
}

/** What the result has in place of a conflict. */
sealed interface ConflictResolution {
    /** Nothing picked yet: Save writes it as conflict markers. */
    data object Unresolved : ConflictResolution

    data object Ours : ConflictResolution

    data object Theirs : ConflictResolution

    data object OursThenTheirs : ConflictResolution

    data object TheirsThenOurs : ConflictResolution

    /** Neither side's change: the base's lines, as when both changes are rejected. */
    data object Base : ConflictResolution

    /** Lines that the user typed, as bytes in the file's encoding and line endings. */
    data class Edited(val text: RawText) : ConflictResolution
}
