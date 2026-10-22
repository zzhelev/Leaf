package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.GetWorktreeUseCase
import dev.app.leaf.domain.usecases.OpenPathInSystemUseCase
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import dev.app.leaf.domain.worktrees.cleanupCount
import dev.app.leaf.ui.BranchesPaneConfig
import dev.app.leaf.ui.ChangesPaneConfig
import dev.app.leaf.ui.ChangesPaneNeeds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

class QuickActionsViewModel @Inject constructor(
    private val refreshDataUseCase: RefreshDataUseCase,
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val openPathInSystemUseCase: OpenPathInSystemUseCase,
    private val branchesPaneConfig: BranchesPaneConfig,
    private val changesPaneConfig: ChangesPaneConfig,
    private val changesPaneNeeds: ChangesPaneNeeds,
    repositoryDataRepository: RepositoryDataRepository,
) : TabViewModel() {
    /** Fork-only: whether "Clean up merged worktrees" has something to remove, as the tab's list last read them. */
    val canCleanUpWorktrees: StateFlow<Boolean> = repositoryDataRepository.worktrees
        .map { (it.dataOrNull()?.cleanupCount() ?: 0) > 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Fork-only: whether the branches pane is folded, which the entry that folds or shows it says. */
    val isBranchesPaneFolded: StateFlow<Boolean> = branchesPaneConfig.isFolded

    fun toggleBranchesPane() = branchesPaneConfig.toggle()

    /** Fork-only: whether the changes pane is folded, which the entry that folds or shows it says. */
    val isChangesPaneFolded: StateFlow<Boolean> = changesPaneConfig.isFolded

    /** Fork-only: whether something holds the changes pane open, which leaves the entry out: it would do nothing. */
    val isChangesPaneHeld: StateFlow<Boolean> = changesPaneNeeds.need
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, changesPaneNeeds.need.value != null)

    fun toggleChangesPane() {
        changesPaneNeeds.toggleUnlessHeld(changesPaneConfig)
    }

    // TODO Implement bunch of methods

    fun refreshRepository() = refreshDataUseCase(DataToRefresh.ALL)

    fun openProjectInFileExplorer() {
        viewModelScope.launch {
            val worktree = getWorktreeUseCase()

            if (worktree is Either.Ok) {
                openPathInSystemUseCase(worktree.value)
            }
        }
    }
}