// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

/**
 * What putting a squashed branch on the latest base would do, checked when "Squash branch into one commit…" opens
 * (fork-only, step A4 of `docs/fork/squash-and-rebase.md`): one `git merge-tree` of the base and the branch, and, where
 * the branch is checked out, the uncommitted changes that the merge's files would meet.
 *
 * [onto] is the base's commit as it was checked, which the squash then goes on, and [newCommits] how many of the base's
 * commits the branch lacks.
 */
sealed interface OntoBaseCheck {
    val onto: OntoBase
    val newCommits: Int

    /** The merge has no conflicts, and nothing is in the way. */
    data class Clean(
        override val onto: OntoBase,
        override val newCommits: Int,
    ) : OntoBaseCheck

    /** The merge conflicts in [files]. */
    data class Conflicts(
        override val onto: OntoBase,
        override val newCommits: Int,
        val files: List<String>,
    ) : OntoBaseCheck

    /** The worktree at [worktreePath], where the branch is checked out, has changes to [files], which the merge changes. */
    data class ChangesInTheWay(
        override val onto: OntoBase,
        override val newCommits: Int,
        val worktreePath: String,
        val files: List<String>,
    ) : OntoBaseCheck
}
