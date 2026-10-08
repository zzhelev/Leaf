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
)

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
 * @param aheadBehindBase how the worktree's branch, or its commit when detached, compares to [WorktreeList.baseBranch].
 * @param lastCommitTime the committer time of the checked out commit, in milliseconds since the epoch.
 */
data class WorktreeInfo(
    val worktree: Worktree,
    val status: WorktreeStatus?,
    val aheadBehindBase: AheadBehind?,
    val lastCommitTime: Long?,
)

/**
 * The worktrees of a repository, main one first.
 *
 * @param baseBranch the full name of the branch the worktrees are compared to (`refs/heads/main`), null when the
 * repository has none of the default candidates.
 */
data class WorktreeList(
    val baseBranch: String?,
    val worktrees: List<WorktreeInfo>,
)
