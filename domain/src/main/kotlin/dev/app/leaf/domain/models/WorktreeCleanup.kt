// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

private const val LOCAL_BRANCH_PREFIX = "refs/heads/"

/** A worktree that changed this recently may have an agent working in it, so cleaning up leaves it unchecked. */
const val RECENT_CHANGE_MS = 5 * 60 * 1000L

/**
 * What "Clean up merged worktrees" reads when its dialog opens (Phase 4.3b): the worktrees, read again as the list may be
 * stale, and when something last changed in the ones that it offers.
 *
 * @param recentChanges when something last changed in an offered worktree, by its path, for the ones that changed less
 * than [RECENT_CHANGE_MS] before the dialog read them.
 */
data class WorktreeCleanup(
    val list: WorktreeList,
    val recentChanges: Map<String, Long> = emptyMap(),
)

/**
 * A worktree that cleaning up offers: a linked worktree whose work is in the base ([WorktreeInfo.merged]), or whose
 * folder is missing.
 *
 * @param recentChange when something last changed in it, if that was less than [RECENT_CHANGE_MS] before the dialog
 * read it. Null otherwise.
 */
data class CleanupCandidate(
    val info: WorktreeInfo,
    val recentChange: Long? = null,
) {
    val worktree: Worktree
        get() = info.worktree

    val isMissing: Boolean
        get() = worktree.prunable != null

    /** An agent's session that still runs has it locked: it's shown, and can't be checked. */
    val isInUse: Boolean
        get() = worktree.agentLock?.isRunning == true

    /**
     * git refuses to remove it without `--force`: it has uncommitted changes or untracked files, or they couldn't be
     * read. A missing worktree has nothing to lose.
     */
    val hasUncommittedChanges: Boolean
        get() = !isMissing && (info.status?.isDirty ?: true)

    /** How many uncommitted changes it has, as `git status` counts them. Null when they couldn't be read. */
    val changes: Int?
        get() = info.status?.let { it.staged + it.unstaged + it.untracked + it.conflicted }

    /**
     * It's locked for a reason that the user should look at: anything but an agent's session that has ended, such as a
     * `claude -p` run, which keeps its lock after it ends.
     */
    val hasLockToLookAt: Boolean
        get() = worktree.locked != null && worktree.agentLock == null

    /** Checked when the dialog opens: nothing would be lost, and nothing says that someone may still use it. */
    val isCheckedAtFirst: Boolean
        get() = !isInUse && !hasUncommittedChanges && recentChange == null && !hasLockToLookAt

    /**
     * The local branch that goes with it (`agent1`, not `refs/heads/agent1`): only a branch whose work is in the base,
     * so a missing worktree's branch with commits of its own stays. Never the base or its local branch, which aren't
     * marked merged.
     */
    val branch: String?
        get() = worktree.branch?.takeIf { info.merged != null && it.startsWith(LOCAL_BRANCH_PREFIX) }
            ?.removePrefix(LOCAL_BRANCH_PREFIX)
}
