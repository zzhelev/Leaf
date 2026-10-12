// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

import dev.app.leaf.domain.models.MergeToolRefusal

/** Errors of resolving conflicts: in the merge tool, or by taking one side. Nothing changed. */
sealed interface ConflictError : GitError {
    /** [paths] have no conflicts (any more): something resolved them after the status was loaded. */
    data class NotConflicted(val paths: List<String>) : ConflictError

    /** Neither `merge.guitool` nor `merge.tool` names a merge tool. */
    data object NoMergeTool : ConflictError

    /** `git mergetool` can't merge the file at [path] from Leaf, for [reason]. */
    data class MergeToolRefused(val path: String, val reason: MergeToolRefusal) : ConflictError

    /**
     * [tool] didn't resolve [path]: it closed without saving it, exited with an error, or couldn't be started. git put
     * the file back as it was, with its conflicts. [output] is what git and the tool printed.
     */
    data class NotResolved(val tool: String, val path: String, val output: String) : ConflictError

    /**
     * [tool], whose exit code git trusts, exited as if it had resolved [path], but the file still has conflict markers,
     * so Leaf put the conflict back (`git update-index --unresolve`). The file is as the tool left it.
     */
    data class MarkersLeft(val tool: String, val path: String) : ConflictError
}
