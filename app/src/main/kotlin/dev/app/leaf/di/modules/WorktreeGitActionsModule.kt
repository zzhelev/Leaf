// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.di.modules

import dev.app.leaf.common.TabScope
import dev.app.leaf.data.git.branches.CheckBranchNameGitAction
import dev.app.leaf.data.git.config.GetWorktreeCreationSettingsGitAction
import dev.app.leaf.data.git.config.SaveWorktreeBaseBranchGitAction
import dev.app.leaf.data.git.config.SaveWorktreeCreationSettingsGitAction
import dev.app.leaf.data.git.worktrees.CopyWorktreeFilesGitAction
import dev.app.leaf.data.git.worktrees.CreateWorktreeIncludeGitAction
import dev.app.leaf.data.git.worktrees.GetAheadBehindGitAction
import dev.app.leaf.data.git.worktrees.GetBranchComparisonGitAction
import dev.app.leaf.data.git.worktrees.GetBranchMergedGitAction
import dev.app.leaf.data.git.worktrees.GetCommitTimesGitAction
import dev.app.leaf.data.git.worktrees.GetCommonGitDirGitAction
import dev.app.leaf.data.git.worktrees.GetPrunableWorktreesGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeActivityGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeBaseBranchGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeIncludeGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeStatusGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreesGitAction
import dev.app.leaf.data.git.worktrees.RunGitCommandGitAction
import dev.app.leaf.domain.interfaces.ICheckBranchNameGitAction
import dev.app.leaf.domain.interfaces.ICopyWorktreeFilesGitAction
import dev.app.leaf.domain.interfaces.ICreateWorktreeIncludeGitAction
import dev.app.leaf.domain.interfaces.IGetAheadBehindGitAction
import dev.app.leaf.domain.interfaces.IGetBranchComparisonGitAction
import dev.app.leaf.domain.interfaces.IGetBranchMergedGitAction
import dev.app.leaf.domain.interfaces.IGetCommitTimesGitAction
import dev.app.leaf.domain.interfaces.IGetCommonGitDirGitAction
import dev.app.leaf.domain.interfaces.IGetPrunableWorktreesGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeBaseBranchGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeCreationSettingsGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeIncludeGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreesGitAction
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import dev.app.leaf.domain.interfaces.ISaveWorktreeBaseBranchGitAction
import dev.app.leaf.domain.interfaces.ISaveWorktreeCreationSettingsGitAction
import dagger.Binds
import dagger.Module

/** Git actions about linked worktrees. Most run the git CLI. */
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
    fun bindsGetWorktreeBaseBranchGitAction(action: GetWorktreeBaseBranchGitAction): IGetWorktreeBaseBranchGitAction

    @Binds
    @TabScope
    fun bindsSaveWorktreeBaseBranchGitAction(action: SaveWorktreeBaseBranchGitAction): ISaveWorktreeBaseBranchGitAction

    @Binds
    @TabScope
    fun bindsGetCommitTimesGitAction(action: GetCommitTimesGitAction): IGetCommitTimesGitAction

    @Binds
    @TabScope
    fun bindsGetCommonGitDirGitAction(action: GetCommonGitDirGitAction): IGetCommonGitDirGitAction

    @Binds
    @TabScope
    fun bindsRunGitCommandGitAction(action: RunGitCommandGitAction): IRunGitCommandGitAction

    @Binds
    @TabScope
    fun bindsGetBranchMergedGitAction(action: GetBranchMergedGitAction): IGetBranchMergedGitAction

    @Binds
    @TabScope
    fun bindsGetWorktreeActivityGitAction(action: GetWorktreeActivityGitAction): IGetWorktreeActivityGitAction

    @Binds
    @TabScope
    fun bindsGetPrunableWorktreesGitAction(action: GetPrunableWorktreesGitAction): IGetPrunableWorktreesGitAction

    @Binds
    @TabScope
    fun bindsCopyWorktreeFilesGitAction(action: CopyWorktreeFilesGitAction): ICopyWorktreeFilesGitAction

    @Binds
    @TabScope
    fun bindsGetWorktreeIncludeGitAction(action: GetWorktreeIncludeGitAction): IGetWorktreeIncludeGitAction

    @Binds
    @TabScope
    fun bindsCreateWorktreeIncludeGitAction(action: CreateWorktreeIncludeGitAction): ICreateWorktreeIncludeGitAction

    @Binds
    @TabScope
    fun bindsCheckBranchNameGitAction(action: CheckBranchNameGitAction): ICheckBranchNameGitAction

    @Binds
    @TabScope
    fun bindsGetWorktreeCreationSettingsGitAction(
        action: GetWorktreeCreationSettingsGitAction,
    ): IGetWorktreeCreationSettingsGitAction

    @Binds
    @TabScope
    fun bindsSaveWorktreeCreationSettingsGitAction(
        action: SaveWorktreeCreationSettingsGitAction,
    ): ISaveWorktreeCreationSettingsGitAction

    @Binds
    @TabScope
    fun bindsGetBranchComparisonGitAction(action: GetBranchComparisonGitAction): IGetBranchComparisonGitAction
}
