// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Branch

/**
 * The commit that the tab's worktree has checked out: the one whose row the log marks (fork-only, upstream issue 255).
 *
 * It is the tip of [currentBranch], which the repository data reads from the tab's own git dir. So it is only the tab's
 * worktree: another worktree's branch, which the log also shows with a chip, has its commit marked in no way. A
 * detached HEAD, also during a rebase or a bisect, comes as a branch named `HEAD` that points at its commit, so it needs
 * no case of its own. A branch that has no commit yet isn't in the branch list, so [currentBranch] is null and nothing
 * is marked. A HEAD whose commit isn't among the log's loaded commits has no row, and nothing is marked either.
 */
fun headCommitHash(currentBranch: Branch?): String? = currentBranch?.hash?.takeIf { it.isNotBlank() }
