// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.restore

import dev.app.leaf.domain.errors.RestoreFileError

/** What the file at the restored path is now, which the confirmation says is replaced. */
enum class CurrentFile {
    /** Nothing is there: it was deleted, or never existed in the working tree. Restoring creates it. */
    MISSING,

    /** A tracked file with no uncommitted changes. */
    UNCHANGED,

    /** A tracked file whose only changes are staged: the working file is the staged version, which stays staged. */
    STAGED_ONLY,

    /** A tracked file that differs from the index. Restoring loses those changes. */
    UNSTAGED_CHANGES,

    /** A file git doesn't track. Restoring loses its content. */
    UNTRACKED,

    /** A file that a gitignore rule hides. Restoring loses its content. */
    IGNORED,
}

/**
 * What restoring [path] from [commit] would do, as the confirmation tells it. [refusal] is set when it can't be done,
 * and then nothing is written.
 */
data class FileRestorePlan(
    val path: String,
    val commit: String,
    val current: CurrentFile,
    /** Whether the file has staged changes, which a restore leaves in the index. */
    val hasStagedChanges: Boolean,
    val refusal: RestoreFileError? = null,
)

/**
 * Reads `git status --porcelain=v1 -z --no-renames` of the single path [path], where [exists] says whether anything is
 * at it in the working tree. [entry] is the status line, `XY <path>`, or null when git printed none for it.
 */
fun currentFileOf(entry: String?, exists: Boolean): Pair<CurrentFile, Boolean> {
    val code = entry?.take(2) ?: return (if (exists) CurrentFile.UNCHANGED else CurrentFile.MISSING) to false
    val staged = code[0] != ' ' && code[0] != '?' && code[0] != '!'

    return when {
        code == "??" -> CurrentFile.UNTRACKED to false
        code == "!!" -> CurrentFile.IGNORED to false
        // Deleted in the working tree, or the deletion is staged: nothing is there to lose
        code[1] == 'D' || !exists -> CurrentFile.MISSING to staged
        code[1] != ' ' -> CurrentFile.UNSTAGED_CHANGES to staged
        else -> CurrentFile.STAGED_ONLY to staged
    }
}

/** Whether a status line says the path has conflicts: an `U` on either side, or both added or both deleted. */
fun isConflictedStatus(entry: String?): Boolean {
    val code = entry?.take(2) ?: return false

    return code[0] == 'U' || code[1] == 'U' || code == "AA" || code == "DD"
}
