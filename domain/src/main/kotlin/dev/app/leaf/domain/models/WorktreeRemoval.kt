// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * Whether a branch is merged into the base branch.
 *
 * @param isMerged the branch's tip is in the base, or merging the branch into the base would change nothing, as after
 * a squash merge.
 * @param commitsNotInBase the commits that the branch has and the base doesn't. A squash-merged branch still has them.
 */
data class BranchMerged(
    val isMerged: Boolean,
    val commitsNotInBase: Int,
)

/**
 * What removing [worktree] would lose, read when its dialog opens: the worktree list may be stale.
 *
 * @param mainWorktree the repository's main worktree, where git runs, and where the tab goes when it shows [worktree].
 * @param status the worktree's changes. Null when its folder is missing, or git couldn't read them.
 * @param lastChange when something last changed in the worktree, in milliseconds since the epoch (see
 * [dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction]). Null when it couldn't be read.
 * @param branch the local branch that removing the worktree can delete (`agent1`). Null when the worktree is detached,
 * or has the base branch checked out.
 * @param baseBranch the full name of the branch the worktrees are compared to, null when there's none.
 * @param branchMerged whether [branch] is merged into [baseBranch]. Null without either, or when git couldn't tell.
 */
data class WorktreeRemoval(
    val worktree: Worktree,
    val mainWorktree: Worktree,
    val status: WorktreeStatus?,
    val lastChange: Long?,
    val branch: String?,
    val baseBranch: String?,
    val branchMerged: BranchMerged?,
) {
    /** The worktree's folder is missing, so there's nothing in it to lose. */
    val isMissing: Boolean
        get() = worktree.prunable != null

    /**
     * Whether removing the worktree deletes uncommitted changes or untracked files, which git refuses without force.
     * When git couldn't read them, they're taken to be there.
     */
    val hasUncommittedChanges: Boolean
        get() = !isMissing && (status?.isDirty ?: true)

    val isLocked: Boolean
        get() = worktree.locked != null
}
