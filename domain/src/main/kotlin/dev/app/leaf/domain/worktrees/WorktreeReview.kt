// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree

/**
 * The branch whose changes "Review changes" shows for this worktree (fork-only): the one it has checked out, compared
 * with [baseBranch], the branch that the worktrees are compared to. Null when the worktree is detached, has the base
 * checked out, or there's no base.
 */
fun Worktree.branchToReview(baseBranch: String?): String? = branch?.takeIf { baseBranch != null && it != baseBranch }
