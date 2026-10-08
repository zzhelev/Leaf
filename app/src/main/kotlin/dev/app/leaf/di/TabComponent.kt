package dev.app.leaf.di

import dev.app.leaf.common.TabScope
import dev.app.leaf.di.modules.FileWatcherModule
import dev.app.leaf.di.modules.TabModule
import dev.app.leaf.di.modules.TabRepositoriesModule
import dev.app.leaf.di.modules.TabScopeGitActionsModule
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.ui.dialogs.*
import dev.app.leaf.viewmodels.*
import dev.app.leaf.viewmodels.sidepanel.SubmoduleDialogViewModel
import dagger.Subcomponent

@TabScope
@Subcomponent(
    modules = [
        TabModule::class,
        TabRepositoriesModule::class,
        FileWatcherModule::class,
        TabScopeGitActionsModule::class,
    ],
)
interface TabComponent {
    @Subcomponent.Factory
    interface Factory {
        fun create(): TabComponent
    }

    fun cloneViewModel(): CloneViewModel
    fun settingsViewModel(): SettingsViewModel
    fun repositoryTabViewModelFactory(): RepositoryTabViewModel.Factory
    fun repositoryOpenViewModel(): RepositoryOpenViewModel
    fun historyViewModel(): HistoryViewModel
    fun authorViewModel(): AuthorViewModel
    fun stashWithMessageViewModel(): StashWithMessageViewModel
    fun quickActionsViewModel(): QuickActionsViewModel
    fun setUpstreamBranchDialogViewModelFactory(): SetUpstreamBranchDialogViewModel.Factory
    fun renameBranchDialogViewModelFactory(): RenameBranchDialogViewModel.Factory
    fun createBranchViewModelFactory(): CreateBranchViewModel.Factory
    fun createTagViewModelFactory(): CreateTagViewModel.Factory
    fun deleteBranchViewModelFactory(): DeleteBranchViewModel.Factory
    fun deleteTagViewModelFactory(): DeleteTagViewModel.Factory
    fun resetBranchViewModelFactory(): ResetBranchViewModel.Factory
    fun discardChangesViewModelFactory(): DiscardChangesViewModel.Factory
    fun addEditRemoteViewModelFactory(): AddEditRemoteViewModel.Factory
    fun submoduleDialogViewModel(): SubmoduleDialogViewModel
    fun signOffDialogViewModel(): SignOffDialogViewModel
}