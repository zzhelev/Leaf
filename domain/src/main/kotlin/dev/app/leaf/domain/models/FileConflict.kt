// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** What one side of a conflict has at the file's path. */
enum class ConflictVersion {
    /** A file, executable or not. */
    FILE,
    SYMLINK,
    SUBMODULE,
}

/** A side of a conflict: ours (stage 2 in the index) or theirs (stage 3). */
enum class ConflictSide {
    OURS,
    THEIRS,
}

/**
 * The versions of a file with conflicts in the index: [ours] (stage 2) and [theirs] (stage 3), each null when that side
 * deleted the file. In a merge, ours is the branch checked out and theirs the branch merged. In a rebase, ours is the
 * commit that the rebase builds on and theirs the commit it replays.
 */
data class FileConflict(val ours: ConflictVersion?, val theirs: ConflictVersion?) {
    fun version(side: ConflictSide) = when (side) {
        ConflictSide.OURS -> ours
        ConflictSide.THEIRS -> theirs
    }
}

/** Why `git mergetool` can't merge a conflicted file from Leaf, or null when it can. */
enum class MergeToolRefusal {
    /**
     * One side deleted the file, or has a symbolic link or a submodule there. git then asks on the terminal which side
     * to keep, even with `--no-prompt`, and Leaf has no terminal to answer.
     */
    NOT_TWO_FILES,

    /**
     * The name has `*`, `?` or `[`. git-mergetool's script lets the shell expand file names as patterns, so it can merge
     * another file whose name matches (`a.txt` for `[ab].txt`).
     */
    FILE_NAME,
}

private val SHELL_PATTERN_CHARACTERS = charArrayOf('*', '?', '[')

/** Why `git mergetool` can't merge the file at [path], which has [conflict], or null when it can. */
fun mergeToolRefusal(path: String, conflict: FileConflict): MergeToolRefusal? = when {
    conflict.ours != ConflictVersion.FILE || conflict.theirs != ConflictVersion.FILE -> MergeToolRefusal.NOT_TWO_FILES
    path.indexOfAny(SHELL_PATTERN_CHARACTERS) >= 0 -> MergeToolRefusal.FILE_NAME
    else -> null
}

/**
 * Whether the file has conflicts. Discarding leaves such a file alone, as git's `restore` does ("path is unmerged"):
 * resetting it would take ours without saying so, while "Use ours" and "Use theirs" name the side they take.
 */
val StatusEntry.hasConflicts: Boolean
    get() = statusType == StatusType.CONFLICTING

/** Whether Leaf can open this conflicted file in the merge tool. */
val StatusEntry.canOpenInMergeTool: Boolean
    get() = conflict != null && mergeToolRefusal(filePath, conflict) == null

/** The operation that stopped at conflicts, which tells what ours and theirs are. */
enum class ConflictOperation {
    MERGE,
    REBASE,
    CHERRY_PICK,
    REVERT,

    /** Something that records no side of its own, such as a stash that didn't apply cleanly. */
    OTHER,
}

/**
 * A commit on one side of a conflict: [branch] when it has one (short, such as `main` or `origin/main`), its
 * abbreviated [hash] and its [subject].
 */
data class ConflictCommit(val branch: String?, val hash: String, val subject: String)

/**
 * What ours and theirs are in the operation that stopped at conflicts, as the UI names them:
 * - a merge: ours is the branch checked out, theirs the branch merged (`MERGE_HEAD`);
 * - a rebase: ours is the branch rebased onto, with the commits replayed so far, and theirs the commit being replayed,
 *   with the branch being rebased;
 * - a cherry-pick: ours is the branch checked out, theirs the commit picked;
 * - a revert: ours is the branch checked out, theirs the commit reverted (its parent's version, in fact).
 *
 * Either is null when git doesn't record it, such as theirs after a stash that didn't apply cleanly.
 */
data class ConflictSides(val operation: ConflictOperation, val ours: ConflictCommit?, val theirs: ConflictCommit?)
