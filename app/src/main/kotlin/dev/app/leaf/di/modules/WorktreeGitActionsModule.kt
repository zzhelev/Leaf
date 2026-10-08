// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.di.modules

import dev.app.leaf.common.TabScope
import dev.app.leaf.data.git.worktrees.GetAheadBehindGitAction
import dev.app.leaf.data.git.worktrees.GetCommitTimesGitAction
import dev.app.leaf.data.git.worktrees.GetDefaultBaseBranchGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeStatusGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreesGitAction
import dev.app.leaf.domain.interfaces.IGetAheadBehindGitAction
import dev.app.leaf.domain.interfaces.IGetCommitTimesGitAction
import dev.app.leaf.domain.interfaces.IGetDefaultBaseBranchGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreesGitAction
import dagger.Binds
import dagger.Module

/** Git actions about linked worktrees, which run the git CLI. */
@Module
interface WorktreeGitActionsModule {
    @Binds
    @TabScope
    fun bindsGetWorktreesGitAction(action: GetWorktreesGitAction): IGetWorktreesGitAction

    @Binds
    @TabScope
    fun bindsGetWorktreeStatusGitAction(action: GetWorktreeStatusGitAction): IGetWorktreeStatusGitAction

    @Binds
    @TabScope
    fun bindsGetAheadBehindGitAction(action: GetAheadBehindGitAction): IGetAheadBehindGitAction

    @Binds
    @TabScope
    fun bindsGetDefaultBaseBranchGitAction(action: GetDefaultBaseBranchGitAction): IGetDefaultBaseBranchGitAction

    @Binds
    @TabScope
    fun bindsGetCommitTimesGitAction(action: GetCommitTimesGitAction): IGetCommitTimesGitAction
}
