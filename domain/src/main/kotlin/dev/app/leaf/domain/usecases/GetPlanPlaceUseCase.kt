// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.models.RECENT_CHANGE_MS
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.usersByBranch
import dev.app.leaf.domain.rewrite.PlanPlace
import java.io.File
import javax.inject.Inject

/**
 * Where a commit editor's plan for [branch] would replay if it stops (fork-only, B5), from the tab's worktree [list]:
 * the worktree that has the branch checked out (the tab's own first), or the tab's, and the uncommitted changes there,
 * read again, as the list may be older. For another worktree, also its last change, if an agent may still be working.
 */
class GetPlanPlaceUseCase @Inject constructor(
    private val getWorktreeStatusGitAction: IGetWorktreeStatusGitAction,
    private val getWorktreeActivityGitAction: IGetWorktreeActivityGitAction,
) {
    suspend operator fun invoke(list: WorktreeList, branch: String, now: Long = System.currentTimeMillis()): PlanPlace {
        val users = list.usersByBranch()[branch]?.users.orEmpty().filter { it.use == WorktreeBranchUse.CheckedOut }
        val user = users.firstOrNull { it.info.worktree.isCurrent } ?: users.firstOrNull()
        val worktree = user?.info?.worktree ?: list.worktrees.firstOrNull { it.worktree.isCurrent }?.worktree
        val isTabs = user == null || user.info.worktree.isCurrent
        val status = worktree?.takeIf { it.prunable == null }?.let { getWorktreeStatusGitAction(it.path).okOrNull() }
        val lastChange = if (!isTabs && worktree != null && worktree.prunable == null) {
            getWorktreeActivityGitAction(worktree.path).okOrNull()
        } else {
            null
        }

        return PlanPlace(
            worktreeName = user?.info?.worktree?.path?.let { File(it).name },
            isTabs = isTabs,
            uncommitted = status?.let { it.staged + it.unstaged + it.conflicted } ?: 0,
            recentChange = lastChange?.takeIf { now - it < RECENT_CHANGE_MS },
        )
    }
}
