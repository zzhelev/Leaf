// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.domain.interfaces.PullHasConflicts
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseResult

/**
 * Whether the merge of a pull stopped at conflicts, which the user then resolves. A merge that couldn't start throws,
 * with the files that stopped it: before, it counted as a pull without conflicts, and Leaf said "Pull completed" when
 * nothing was merged.
 */
fun mergeHasConflicts(result: MergeResult): PullHasConflicts = when (result.mergeStatus) {
    MergeResult.MergeStatus.CONFLICTING -> true

    MergeResult.MergeStatus.FAILED -> throw PullWouldOverwriteException(result.failingPaths.orEmpty().keys)
    MergeResult.MergeStatus.CHECKOUT_CONFLICT -> throw PullWouldOverwriteException(result.checkoutConflicts.orEmpty())

    // What a merge that may only fast-forward (pull.ff or merge.ff set to only) returns for diverged branches
    MergeResult.MergeStatus.ABORTED -> throw Exception(
        "The pull didn't merge, as your branch and the one pulled have diverged, and pull.ff or merge.ff only " +
            "allows fast-forwards."
    )

    MergeResult.MergeStatus.NOT_SUPPORTED -> throw Exception("The pull didn't merge: ${result.mergeStatus}")

    else -> false
}

/** A pull that didn't merge, as it would overwrite the local changes to [paths]. Nothing was changed. */
class PullWouldOverwriteException(paths: Collection<String>) : Exception(
    "The pull didn't merge, as it would overwrite your changes to ${paths.sorted().joinToString(", ")}. Commit or " +
        "stash them, then pull again."
)

/**
 * Whether the rebase of a pull stopped at conflicts, like [mergeHasConflicts]. JGit's CONFLICTS isn't one: local files
 * stopped the checkout, and the branch is as it was. FAILED is the same for a commit that the rebase couldn't apply.
 */
fun rebaseHasConflicts(result: RebaseResult): PullHasConflicts = when (result.status) {
    RebaseResult.Status.STOPPED -> true

    RebaseResult.Status.CONFLICTS -> throw PullWouldOverwriteException(result.conflicts.orEmpty())
    RebaseResult.Status.FAILED -> throw PullWouldOverwriteException(result.failingPaths.orEmpty().keys)

    RebaseResult.Status.UNCOMMITTED_CHANGES ->
        throw Exception("The pull with rebase has failed because you have got uncommitted changes")

    else -> if (result.status.isSuccessful) false else throw Exception("Pull failed")
}
