// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * How a worktree uses a branch, which keeps git from checking the branch out in another worktree: a commit there would
 * move the branch under this worktree's files.
 */
enum class WorktreeBranchUse {
    /** The worktree has the branch checked out. */
    CheckedOut,

    /** The worktree is rebasing the branch, so its HEAD is detached until the rebase ends. */
    Rebasing,

    /** The worktree is bisecting, started from the branch, so its HEAD is detached until `git bisect reset`. */
    Bisecting,
}
