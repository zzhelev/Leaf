// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** How a worktree's branch goes into the base branch. */
enum class WorktreeMergeKind {
    /** `git merge --squash`: the branch's changes staged as one change, for the user to commit. */
    SQUASH,

    /** `git merge --no-ff --no-commit`: a merge commit, which the user commits with the merge's message. */
    MERGE,
}

/**
 * What merging [worktree]'s branch into the base's local branch needs (fork-only), read when its dialog opens: the
 * worktree list may be stale.
 *
 * @param branch the branch that is merged, the one [worktree] has checked out (`refs/heads/agent1`).
 * @param branchArgument how the merge names [branch]: its short name (`agent1`), or its full name when the short one
 * means something else to git, such as a tag of the same name.
 * @param target the local branch that [branch] is merged into (`refs/heads/main`).
 * @param targetWorktree the worktree that has [target] checked out, where the merge runs. Null when none has.
 * @param targetStatus [targetWorktree]'s changes, null without it or when git couldn't read them.
 * @param targetUser a worktree that rebases [target] or bisects from it, while its HEAD is detached. Null otherwise.
 * @param tabWorktree the tab's worktree, which checks [target] out first when no worktree has it.
 * @param tabStatus [tabWorktree]'s changes, null when git couldn't read them.
 * @param branchStatus [worktree]'s changes, which the merge leaves out. Null when its folder is missing, or git
 * couldn't read them.
 * @param lastChange when something last changed in [worktree], in milliseconds since the epoch. Null when it couldn't
 * be read.
 * @param commits the commits that [branch] has and [target] doesn't, oldest first, at most [MAX_COMPARED_COMMITS].
 * @param commitCount how many there are in all.
 * @param branchMerged whether [branch]'s changes are already in [target]. Null when git couldn't tell.
 */
data class WorktreeMerge(
    val worktree: Worktree,
    val branch: String,
    val branchArgument: String,
    val target: String,
    val targetWorktree: Worktree?,
    val targetStatus: WorktreeStatus?,
    val targetUser: Worktree?,
    val tabWorktree: Worktree,
    val tabStatus: WorktreeStatus?,
    val branchStatus: WorktreeStatus?,
    val lastChange: Long?,
    val commits: List<Commit>,
    val commitCount: Int,
    val branchMerged: BranchMerged?,
)

/** Why a worktree's branch can't be merged into the base, which its dialog says instead of merging. */
sealed interface WorktreeMergeRefusal {
    /** The branch has no commits that the target doesn't have. */
    data object NothingToMerge : WorktreeMergeRefusal

    /** The branch's changes are already in the target, as after a squash merge, so a squash would stage nothing. */
    data object AlreadyInTarget : WorktreeMergeRefusal

    /** The target's worktree has staged changes or conflicts, which a squash would take in and a merge refuses. */
    data class TargetHasChanges(val worktree: Worktree, val staged: Int, val conflicted: Int) : WorktreeMergeRefusal

    /** git couldn't read the target worktree's changes, so they can't be ruled out. */
    data class TargetStatusUnknown(val worktree: Worktree) : WorktreeMergeRefusal

    /** The target worktree's folder is missing. */
    data class TargetWorktreeMissing(val worktree: Worktree) : WorktreeMergeRefusal

    /** A worktree rebases the target or bisects from it, so the target can't take a merge until that ends. */
    data class TargetInUse(val worktree: Worktree, val use: WorktreeBranchUse) : WorktreeMergeRefusal

    /** No worktree has the target, and the tab's worktree is the branch's own, so it can't check the target out. */
    data object TabWorktreeIsBranchWorktree : WorktreeMergeRefusal

    /** No worktree has the target, and the tab's worktree has changes, so it shouldn't check the target out. */
    data class TabWorktreeHasChanges(val worktree: Worktree) : WorktreeMergeRefusal
}
