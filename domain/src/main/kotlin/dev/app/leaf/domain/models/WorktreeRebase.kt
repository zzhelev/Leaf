// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * What rebasing [worktree]'s branch onto the base needs (fork-only), read when its dialog opens: the worktree list may
 * be stale.
 *
 * @param branch the branch that is rebased, the one [worktree] has checked out (`refs/heads/agent1`).
 * @param base the branch that it's rebased onto, the base as chosen (`refs/heads/main`, `refs/remotes/origin/main`).
 * @param baseArgument how the rebase names [base]: its short name (`main`, `origin/main`), or its full name when the
 * short one means something else to git, such as a tag of the same name.
 * @param status [worktree]'s changes, null when its folder is missing or git couldn't read them.
 * @param lastChange when something last changed in [worktree], in milliseconds since the epoch. Null when it couldn't
 * be read.
 * @param commitCount how many commits [branch] has that [base] doesn't, the ones that the rebase replays.
 * @param baseCommitCount how many commits [base] has that [branch] doesn't, the ones that the rebase brings in.
 */
data class WorktreeRebase(
    val worktree: Worktree,
    val branch: String,
    val base: String,
    val baseArgument: String,
    val status: WorktreeStatus?,
    val lastChange: Long?,
    val commitCount: Int,
    val baseCommitCount: Int,
)

/** Why a worktree's branch can't be rebased onto the base, which its dialog says instead of rebasing. */
sealed interface WorktreeRebaseRefusal {
    /** The branch already has every commit of the base. */
    data object UpToDate : WorktreeRebaseRefusal

    /** The worktree's folder is missing. */
    data object WorktreeMissing : WorktreeRebaseRefusal

    /** git couldn't read the worktree's changes, so they can't be ruled out. */
    data object StatusUnknown : WorktreeRebaseRefusal

    /**
     * The worktree has changes to tracked files, staged, unstaged or with conflicts, which git refuses to rebase with.
     * Leaf doesn't stash them for it: they may be an agent's work in progress.
     */
    data class HasChanges(val changes: Int) : WorktreeRebaseRefusal
}
