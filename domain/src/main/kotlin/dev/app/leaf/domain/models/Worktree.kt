// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * A working tree of a repository, as `git worktree list` reports it: the main one, which holds the repository's
 * `.git` folder, or a linked one created with `git worktree add`.
 *
 * @param headSha the checked out commit, null in a bare repository.
 * @param branch the full name of the checked out branch (`refs/heads/x`, as in [Branch.name]), null when detached.
 * @param isMain whether this is the main worktree. Git always lists it first.
 * @param isCurrent whether this is the worktree that the tab shows.
 * @param locked null if the worktree isn't locked, otherwise the reason given to `git worktree lock`, maybe empty.
 * @param prunable null unless `git worktree prune` would remove the worktree (for example, its folder was deleted),
 * otherwise git's reason.
 * @param rebasingBranch the branch that the worktree rebases while its HEAD is detached (`refs/heads/x`), which
 * `git worktree list` doesn't tell. Null otherwise.
 * @param bisectingBranch the branch that the worktree's bisect started from while its HEAD is detached
 * (`refs/heads/x`). Null otherwise.
 */
data class Worktree(
    val path: String,
    val headSha: String?,
    val branch: String?,
    val isMain: Boolean,
    val isCurrent: Boolean,
    val isDetached: Boolean,
    val isBare: Boolean,
    val locked: String?,
    val prunable: String?,
    val rebasingBranch: String? = null,
    val bisectingBranch: String? = null,
) {
    /**
     * How this worktree uses the local branch [branchName] (`refs/heads/x`), as git tells when it refuses to check the
     * branch out in another worktree (`is_shared_symref`): its HEAD is the branch, or its HEAD is detached while it
     * rebases the branch or bisects from it. Null when it doesn't use it.
     */
    fun useOf(branchName: String): WorktreeBranchUse? = when {
        branch != null -> if (branch == branchName) WorktreeBranchUse.CheckedOut else null
        rebasingBranch == branchName -> WorktreeBranchUse.Rebasing
        bisectingBranch == branchName -> WorktreeBranchUse.Bisecting
        else -> null
    }

    /** The local branches this worktree uses (`refs/heads/x`), see [useOf]. */
    val usedBranches: List<String>
        get() = if (branch != null) listOf(branch) else listOfNotNull(rebasingBranch, bisectingBranch).distinct()
}

/**
 * The changes in a worktree, from `git status`. The counts are of `git status` entries, so a new folder whose files
 * are all untracked counts once.
 *
 * @param upstream the upstream branch, for example `origin/main`; null when the branch has none.
 * @param upstreamAheadBehind null when there is no upstream, or it no longer exists.
 */
data class WorktreeStatus(
    val staged: Int,
    val unstaged: Int,
    val untracked: Int,
    val conflicted: Int,
    val upstream: String?,
    val upstreamAheadBehind: AheadBehind?,
) {
    val isDirty: Boolean get() = staged + unstaged + untracked + conflicted > 0
}

/** The commits that a branch has and its base doesn't ([ahead]), and the other way round ([behind]). */
data class AheadBehind(
    val ahead: Int,
    val behind: Int,
)

/**
 * What Leaf shows about a worktree. A part is null when it doesn't apply (a prunable worktree has no status) or
 * couldn't be read.
 *
 * @param aheadBehindBase how the worktree's branch compares to [WorktreeList.baseBranch]: the branch it has checked
 * out, or rebases or bisects from, otherwise its commit.
 * @param lastCommitTime the committer time of the checked out commit, in milliseconds since the epoch.
 */
data class WorktreeInfo(
    val worktree: Worktree,
    val status: WorktreeStatus?,
    val aheadBehindBase: AheadBehind?,
    val lastCommitTime: Long?,
)

/**
 * The branch that the worktrees of a repository are compared to.
 *
 * @param automatic the local branch that Leaf picks by itself (`refs/heads/main`): the one `origin/HEAD` points to,
 * then `main`, then `master`, whichever exists first. Null when none does.
 * @param chosen the branch chosen for the repository, local or remote (`refs/heads/develop`,
 * `refs/remotes/origin/main`). Null when the choice is automatic.
 * @param chosenExists whether [chosen] exists. While it doesn't, the worktrees are compared to [automatic].
 */
data class WorktreeBaseBranch(
    val automatic: String?,
    val chosen: String? = null,
    val chosenExists: Boolean = false,
) {
    /** The full name of the branch that the worktrees are compared to, null when there's none. */
    val effective: String?
        get() = if (chosen != null && chosenExists) chosen else automatic
}

/**
 * The worktrees of a repository, main one first.
 *
 * @param base the branch that the worktrees are compared to.
 */
data class WorktreeList(
    val base: WorktreeBaseBranch,
    val worktrees: List<WorktreeInfo>,
) {
    /** The full name of the branch the worktrees are compared to (`refs/heads/main`), null when there's none. */
    val baseBranch: String?
        get() = base.effective
}

/** A worktree that uses a local branch, and how. */
data class WorktreeBranchUser(
    val info: WorktreeInfo,
    val use: WorktreeBranchUse,
)

/**
 * The worktrees that use a local branch, main one first, and what Leaf's guards refuse to do with the branch because of
 * them, as git does. The guards still decide: this only comes from the last time the worktrees were read.
 */
data class BranchWorktreeUsers(
    val users: List<WorktreeBranchUser>,
) {
    /** The first worktree other than the tab's, which the branch list names. Null when only the tab's uses it. */
    val other: WorktreeBranchUser?
        get() = users.firstOrNull { !it.info.worktree.isCurrent }

    /** The branch can't be checked out while another worktree uses it. */
    val canCheckout: Boolean
        get() = other == null

    /** The branch can't be deleted while a worktree uses it, the tab's included. */
    val canDelete: Boolean
        get() = users.isEmpty()

    /** The branch can't be renamed while a worktree rebases it or bisects from it, the tab's included. */
    val canRename: Boolean
        get() = users.all { it.use == WorktreeBranchUse.CheckedOut }
}

/** The worktrees that use each local branch (`refs/heads/x`), for the branches that any worktree uses. */
fun WorktreeList.usersByBranch(): Map<String, BranchWorktreeUsers> {
    return worktrees
        .flatMap { info ->
            info.worktree.usedBranches.mapNotNull { branch ->
                info.worktree.useOf(branch)?.let { use -> branch to WorktreeBranchUser(info, use) }
            }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, users) -> BranchWorktreeUsers(users) }
}
