// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeMerge
import dev.app.leaf.domain.models.WorktreeMergeKind
import dev.app.leaf.domain.models.WorktreeMergeRefusal

private const val LOCAL_BRANCH_PREFIX = "refs/heads/"
private const val REMOTE_BRANCH_PREFIX = "refs/remotes/"

/**
 * The local branch that the worktrees are merged into (fork-only): [baseBranch] when it's local, or the local branch
 * of a remote base (`main` for `origin/main`) among [localBranches] (full names). Null without a base, or when a remote
 * base has no local branch. A remote's name can have a `/` too, so the longest branch name that fits wins:
 * `origin/feat/x` gives `feat/x` rather than `x`.
 */
fun mergeTarget(baseBranch: String?, localBranches: Collection<String>): String? = when {
    baseBranch == null -> null
    baseBranch.startsWith(LOCAL_BRANCH_PREFIX) -> baseBranch
    baseBranch.startsWith(REMOTE_BRANCH_PREFIX) -> {
        val remoteBranch = baseBranch.removePrefix(REMOTE_BRANCH_PREFIX)

        localBranches
            .filter { it.startsWith(LOCAL_BRANCH_PREFIX) }
            .filter { branch ->
                val name = branch.removePrefix(LOCAL_BRANCH_PREFIX)
                remoteBranch.endsWith("/$name") && remoteBranch.length > name.length + 1
            }
            .maxByOrNull { it.length }
    }

    else -> null
}

/**
 * The branch that "Squash-merge into" and "Merge into" would merge into [target] (fork-only): the one this worktree has
 * checked out, when it's neither [target] nor [baseBranch]. Null otherwise, and for a detached worktree.
 */
fun Worktree.branchToMerge(baseBranch: String?, target: String?): String? =
    branchToReview(baseBranch)?.takeIf { target != null && it != target }

/** Whether the tab's worktree has changes to tracked files, or git couldn't read them. */
private fun WorktreeMerge.tabWorktreeHasChanges(): Boolean {
    val status = tabStatus ?: return true

    return status.staged + status.unstaged + status.conflicted > 0
}

/**
 * Why the branch can't be merged this way, or null when it can. git would refuse some of these too, but not all:
 * `git merge --squash` takes the target worktree's staged changes into the squash without a word.
 *
 * When no worktree has the target, the tab's worktree checks it out first, unless it's the branch's own worktree or
 * it has changes to tracked files, which `git switch` would carry over. Untracked files don't stop it: git refuses to
 * overwrite them.
 */
fun WorktreeMerge.refusal(kind: WorktreeMergeKind): WorktreeMergeRefusal? {
    if (commitCount == 0) {
        return WorktreeMergeRefusal.NothingToMerge
    }

    if (kind == WorktreeMergeKind.SQUASH && branchMerged?.isMerged == true) {
        return WorktreeMergeRefusal.AlreadyInTarget
    }

    val targetUser = targetUser
    val targetUse = targetUser?.useOf(target)

    if (targetUser != null && targetUse != null) {
        return WorktreeMergeRefusal.TargetInUse(targetUser, targetUse)
    }

    val targetWorktree = targetWorktree

    if (targetWorktree != null) {
        if (targetWorktree.prunable != null) {
            return WorktreeMergeRefusal.TargetWorktreeMissing(targetWorktree)
        }

        val status = targetStatus ?: return WorktreeMergeRefusal.TargetStatusUnknown(targetWorktree)

        return if (status.staged > 0 || status.conflicted > 0) {
            WorktreeMergeRefusal.TargetHasChanges(targetWorktree, status.staged, status.conflicted)
        } else {
            null
        }
    }

    return when {
        tabWorktree.path == worktree.path -> WorktreeMergeRefusal.TabWorktreeIsBranchWorktree
        tabWorktreeHasChanges() -> WorktreeMergeRefusal.TabWorktreeHasChanges(tabWorktree)
        else -> null
    }
}

/**
 * The steps that merge the branch into the target, in the worktree that has the target checked out: the tab switches
 * there first, so that the staged result, the message and any conflicts show in it. When no worktree has the target,
 * the tab's worktree checks it out first (`git switch`).
 *
 * The merge may stop at conflicts, which the steps that follow don't mind. A squash then puts [message] in
 * `SQUASH_MSG`, where the commit field and a commit from a terminal both find it.
 */
fun WorktreeMerge.mergeSteps(kind: WorktreeMergeKind, message: String): List<WorktreeStep> = buildList {
    val targetWorktree = targetWorktree
    val folder = targetWorktree?.path ?: tabWorktree.path

    when {
        targetWorktree == null -> {
            add(WorktreeStep.Git(folder, listOf("switch", target.removePrefix(LOCAL_BRANCH_PREFIX))))
        }

        !targetWorktree.isCurrent -> add(WorktreeStep.SwitchTab(folder))
    }

    val mergeArgs = when (kind) {
        WorktreeMergeKind.SQUASH -> listOf("merge", "--squash", branchArgument)
        WorktreeMergeKind.MERGE -> listOf("merge", "--no-ff", "--no-commit", branchArgument)
    }

    add(WorktreeStep.Git(folder, mergeArgs, mayStopAtConflicts = true))

    if (kind == WorktreeMergeKind.SQUASH) {
        add(WorktreeStep.WriteSquashMessage(folder, message))
    }
}

/**
 * The commit message of a squash merge (fork-only): the commit's own message when [branch] has one commit, otherwise
 * the branch's name, then a `- <subject>` line per commit, oldest first.
 *
 * @param commits the newest commits that the branch brings, oldest first.
 * @param commitCount how many it brings in all. A line counts those that [commits] leaves out.
 */
fun squashMessage(branch: String, commits: List<Commit>, commitCount: Int): String {
    if (commitCount == 1 && commits.size == 1) {
        return commits.single().message.trim()
    }

    val olderCommits = commitCount - commits.size

    return buildString {
        append(branch.removePrefix(LOCAL_BRANCH_PREFIX))
        append("\n")

        if (olderCommits > 0) {
            append("\n- ($olderCommits older ${if (olderCommits == 1) "commit" else "commits"})")
        }

        for (commit in commits) {
            append("\n- ")
            append(commit.shortMessage.trim())
        }
    }
}

/**
 * Whether the worktree is in the middle of a squash merge (fork-only): git's `SQUASH_MSG` is there ([squashMessage])
 * while something is staged or has conflicts. git records nothing else for it, so the repository's state stays SAFE.
 * A `SQUASH_MSG` that a commit left behind (JGit's commit doesn't remove it) doesn't count once nothing is staged.
 */
fun isSquashMergeInProgress(repositoryState: RepositoryState?, squashMessage: String?, status: Status?): Boolean {
    if (repositoryState != RepositoryState.SAFE || squashMessage == null || status == null) {
        return false
    }

    return status.staged.isNotEmpty() || status.unstaged.any { it.statusType == StatusType.CONFLICTING }
}
