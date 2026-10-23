// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * Why a branch's commits weren't rewritten (squash, fixup, reword), or a rewrite wasn't undone (fork-only). All of them
 * are found before the branch moves, so nothing changed.
 */
sealed interface RewriteBranchError : GitError {
    /** [branch] isn't at the commit it was at when the rewrite was planned: something committed, reset or rebased it. */
    data class BranchMoved(val branch: String) : RewriteBranchError

    /** [commit] isn't where the plan has it on [branch]'s first-parent line. */
    data class NotOnLine(val branch: String, val commit: String) : RewriteBranchError

    /** The plan joins [merge], a merge commit, whose other parents would be lost. */
    data class JoinsMerge(val merge: String) : RewriteBranchError

    /**
     * [merge], a commit after the rewritten ones, brings some of them in again through another parent, from a branch
     * that started on [branch]. The new history would have both the old commits and the new ones.
     */
    data class MergedAgain(val branch: String, val merge: String) : RewriteBranchError

    /** The worktree at [worktreePath] has [branch] in the middle of [operation]. */
    data class BranchBusy(
        val branch: String,
        val worktreePath: String,
        val operation: BranchOperation,
    ) : RewriteBranchError

    /** A new message is empty once git's clean-up has run. */
    data object EmptyMessage : RewriteBranchError
}

/** What a worktree does with a branch that stops a rewrite of it. */
enum class BranchOperation {
    MERGE,
    CHERRY_PICK,
    REVERT,
    REBASE,
    BISECT,

    /** `git am`, which keeps its state where a rebase does. */
    APPLY_PATCHES,
}
