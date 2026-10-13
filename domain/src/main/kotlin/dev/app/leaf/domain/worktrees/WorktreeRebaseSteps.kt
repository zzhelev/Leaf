// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRebase
import dev.app.leaf.domain.models.WorktreeRebaseRefusal

/**
 * The branch that "Rebase onto" would rebase onto [baseBranch] (fork-only): the one this worktree has checked out,
 * when it's neither [baseBranch] nor [mergeTarget], the base's local branch. Rebasing `main` onto `origin/main` would
 * be a pull. Null otherwise, and for a detached worktree.
 */
fun Worktree.branchToRebase(baseBranch: String?, mergeTarget: String?): String? =
    branchToReview(baseBranch)?.takeIf { it != mergeTarget }

/**
 * Why the branch can't be rebased, or null when it can. Untracked files don't stop it: git refuses to overwrite them,
 * and changes nothing then.
 */
fun WorktreeRebase.refusal(): WorktreeRebaseRefusal? {
    if (worktree.prunable != null) {
        return WorktreeRebaseRefusal.WorktreeMissing
    }

    val status = status ?: return WorktreeRebaseRefusal.StatusUnknown
    val changes = status.staged + status.unstaged + status.conflicted

    return when {
        changes > 0 -> WorktreeRebaseRefusal.HasChanges(changes)
        baseCommitCount == 0 -> WorktreeRebaseRefusal.UpToDate
        else -> null
    }
}

/**
 * The steps that rebase the branch onto the base, in the branch's worktree. The tab stays where it is, unless the
 * rebase stops at conflicts in another worktree: then it switches there, where they can be resolved.
 *
 * `--no-autostash` keeps git from stashing changes made after the dialog checked, as `rebase.autoStash` would: they may
 * be an agent's work in progress. git refuses to rebase with them instead.
 */
fun WorktreeRebase.rebaseSteps(): List<WorktreeStep> = buildList {
    val folder = worktree.path

    add(WorktreeStep.Git(folder, listOf("rebase", "--no-autostash", baseArgument), mayStopAtConflicts = true))

    if (!worktree.isCurrent) {
        add(WorktreeStep.SwitchTab(folder, onlyIfStoppedAtConflicts = true))
    }
}
