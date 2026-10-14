// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

import dev.app.leaf.domain.models.MergeToolRefusal

/** Errors of resolving conflicts: in the merge tool, or by taking one side. Nothing changed. */
sealed interface ConflictError : GitError {
    /** [paths] have no conflicts (any more): something resolved them after the status was loaded. */
    data class NotConflicted(val paths: List<String>) : ConflictError

    /**
     * Discarding was asked for [paths], which have conflicts: something stopped at conflicts after the status was
     * loaded, as the menus leave such files out. Resetting them would take HEAD's version without saying which side
     * that is, so nothing was discarded, as `git restore` refuses ("path is unmerged").
     */
    data class CantDiscard(val paths: List<String>) : ConflictError

    /** Neither `merge.guitool` nor `merge.tool` names a merge tool. */
    data object NoMergeTool : ConflictError

    /** `git mergetool` can't merge the file at [path] from Leaf, for [reason]. */
    data class MergeToolRefused(val path: String, val reason: MergeToolRefusal) : ConflictError

    /**
     * [tool] didn't resolve [path]: it closed without saving it, exited with an error, or couldn't be started. git put
     * the file back as it was, with its conflicts. [output] is what git and the tool printed. [resolvedBefore] files
     * were resolved before it, when the tool went through several in turn.
     */
    data class NotResolved(
        val tool: String,
        val path: String,
        val output: String,
        val resolvedBefore: Int = 0,
    ) : ConflictError

    /**
     * [tool], whose exit code git trusts, exited as if it had resolved [path], but the file still has conflict markers,
     * so Leaf put the conflict back (`git update-index --unresolve`). The file is as the tool left it. [resolvedBefore]
     * files were resolved before it, when the tool went through several in turn.
     */
    data class MarkersLeft(val tool: String, val path: String, val resolvedBefore: Int = 0) : ConflictError

    /**
     * The index's versions of [path] changed since the resolver loaded it: another merge, rebase or cherry-pick stopped
     * there. Nothing was written.
     */
    data class StagesChanged(val path: String) : ConflictError

    /**
     * A file renamed on both sides whose content has conflicts, in an operation that doesn't tell which commit theirs
     * is: its versions can't be put back as a conflict of its content. Nothing was changed.
     */
    data class RenamedContentUnknown(val path: String) : ConflictError

    /** Something changed the working file at [path] since the resolver loaded it. Nothing was written. */
    data class WorkingFileChanged(val path: String) : ConflictError

    /** A folder or a symbolic link is at [path], where the resolver writes the file. Nothing was written. */
    data class NotAFile(val path: String) : ConflictError

    /** [path] still has [count] conflicts that no resolution was picked for, so it can't be marked resolved. */
    data class ConflictsLeft(val path: String, val count: Int) : ConflictError
}
