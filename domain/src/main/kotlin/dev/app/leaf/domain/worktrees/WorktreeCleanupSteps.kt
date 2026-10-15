// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.CleanupCandidate
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeCleanup
import dev.app.leaf.domain.models.WorktreeList

/**
 * The worktrees that "Clean up merged worktrees" offers (Phase 4.3b), in the order that its dialog lists them: the ones
 * checked at first, then the ones to look at, then the ones in use, each in git's order. Linked worktrees whose work is
 * in the base, and linked worktrees whose folder is missing. Untouched worktrees that aren't merged aren't offered:
 * removing them loses commits.
 */
val WorktreeCleanup.candidates: List<CleanupCandidate>
    get() = list.worktrees
        .filter { info ->
            val worktree = info.worktree

            !worktree.isMain && !worktree.isBare && (info.merged != null || worktree.prunable != null)
        }
        .map { CleanupCandidate(it, recentChanges[it.worktree.path]) }
        .sortedBy { candidate ->
            when {
                candidate.isCheckedAtFirst -> 0
                !candidate.isInUse -> 1
                else -> 2
            }
        }

/**
 * How many worktrees cleaning up can remove, as the list last read them: merged or missing, and not in use by an agent's
 * running session. The header's menu, a merged row's menu and the Actions list offer it while there's one.
 */
fun WorktreeList.cleanupCount(): Int = WorktreeCleanup(this).candidates.count { !it.isInUse }

/** The paths of the worktrees that are checked when the dialog opens. */
fun List<CleanupCandidate>.checkedAtFirst(): Set<String> =
    filter { it.isCheckedAtFirst }.map { it.worktree.path }.toSet()

/**
 * The steps that remove [candidates], run in the main worktree [mainWorktree]: for each, in their order, `git worktree
 * unlock` when it's locked, `git worktree remove`, with `--force` when it has changes, and `git branch -D` when
 * [deleteBranches] and it has a branch to delete ([CleanupCandidate.branch]). The tab switches to the main worktree
 * first when it shows one of them. A worktree in use is never removed.
 *
 * Each worktree is removed by itself, a missing one too, which git allows: `git worktree prune` would also remove the
 * missing worktrees that the user didn't check. A branch goes after its worktree, as git refuses to delete a branch
 * that a worktree has checked out, even one whose folder is missing. `-D`, as a squash-merged branch fails `-d`.
 *
 * A worktree that was clean when the dialog read it gets no `--force`, so git refuses it, and the steps stop, when
 * something changed in it since.
 */
fun cleanupSteps(
    mainWorktree: Worktree,
    candidates: List<CleanupCandidate>,
    deleteBranches: Boolean,
): List<WorktreeStep> = buildList {
    val folder = mainWorktree.path
    val removed = candidates.filter { !it.isInUse && !it.worktree.isMain }

    if (removed.any { it.worktree.isCurrent }) {
        add(WorktreeStep.SwitchTab(folder))
    }

    for (candidate in removed) {
        val path = pathArgument(candidate.worktree.path, folder)

        if (candidate.worktree.locked != null) {
            add(WorktreeStep.Git(folder, listOf("worktree", "unlock", path)))
        }

        val force = if (candidate.hasUncommittedChanges) listOf("--force") else emptyList()

        add(WorktreeStep.Git(folder, listOf("worktree", "remove") + force + path))

        val branch = candidate.branch

        if (deleteBranches && branch != null) {
            add(WorktreeStep.Git(folder, listOf("branch", "-D", branch)))
        }
    }
}
