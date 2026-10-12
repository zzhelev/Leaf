// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** Where a new worktree starts from (fork-only): what the user created it from. */
sealed interface NewWorktreeStart {
    /** The base branch of the worktrees, or the tab's branch when there's none: the Worktrees header and the picker. */
    data object Base : NewWorktreeStart

    /** A local branch, which the new worktree checks out when no worktree uses it, or else branches from. */
    data class LocalBranch(val branch: Branch) : NewWorktreeStart

    /** A remote branch, which the new worktree's branch tracks. */
    data class RemoteBranch(val branch: Branch) : NewWorktreeStart

    data class Commit(val commit: dev.app.leaf.domain.models.Commit) : NewWorktreeStart
}

/**
 * How new worktrees of a repository are made, kept per repository (fork-only).
 *
 * @param folder the folder that new worktrees go in, null for next to the main worktree.
 * @param switchToNew whether the tab switches to a worktree once it's created.
 */
data class WorktreeCreationSettings(
    val folder: String? = null,
    val switchToNew: Boolean = true,
)

/**
 * The repository's `.worktreeinclude` (fork-only), in the main worktree: the gitignored files to copy into a new
 * worktree, which Claude Code, Codex and Conductor read too.
 *
 * @param path the file's path.
 * @param files the files to copy, relative to the main worktree: those that the file's patterns match and git ignores.
 */
data class WorktreeInclude(
    val path: String,
    val files: List<String>,
)

/**
 * What the new worktree dialog needs to know about the repository, read when it opens.
 *
 * @param baseBranch the full name of the branch that the worktrees are compared to, null when there's none.
 * @param currentBranch the tab's branch, null when its HEAD is detached.
 * @param currentCommit the commit that the tab has checked out.
 * @param localBranches the full names of the local branches.
 * @param branchesInUse the full names of the local branches that a worktree uses, which git won't check out again.
 */
data class NewWorktreeContext(
    val mainWorktree: Worktree,
    val baseBranch: String?,
    val currentBranch: Branch?,
    val currentCommit: String?,
    val localBranches: Set<String>,
    val branchesInUse: Set<String>,
    val settings: WorktreeCreationSettings,
)
