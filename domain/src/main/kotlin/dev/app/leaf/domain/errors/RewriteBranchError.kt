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

    /** Putting [branch] on [base] (both short names) would conflict in [files]. */
    data class ConflictsWithBase(
        val branch: String,
        val base: String,
        val files: List<String>,
    ) : RewriteBranchError

    /**
     * Uncommitted changes in the worktree at [worktreePath], which has [branch] checked out, are in the way of the new
     * commit's files: changes to [files], or untracked files where it has some. [gitOutput] is what git said.
     */
    data class ChangesInTheWay(
        val branch: String,
        val worktreePath: String,
        val files: List<String>,
        val gitOutput: String,
    ) : RewriteBranchError

    /**
     * The commit editor's plan for [branch] stops at [commit], with conflicts in [files] (none for an edit), or may stop
     * where Leaf can't tell ([commit] is null), so it can't run without a working tree.
     */
    data class PlanStops(
        val branch: String,
        val commit: String?,
        val files: List<String>,
    ) : RewriteBranchError

    /** The plan changes the commits below [merge], which would have to be merged again. */
    data class ReplaysMerge(val merge: String) : RewriteBranchError

    /** A plan for [branch] that stops needs a working tree to rebase in, which a bare repository hasn't. */
    data class NoWorkingTree(val branch: String) : RewriteBranchError
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
