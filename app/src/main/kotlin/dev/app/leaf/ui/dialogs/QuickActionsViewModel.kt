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
    repositoryDataRepository: RepositoryDataRepository,
) : TabViewModel() {
    /** Fork-only: whether "Clean up merged worktrees" has something to remove, as the tab's list last read them. */
    val canCleanUpWorktrees: StateFlow<Boolean> = repositoryDataRepository.worktrees
        .map { (it.dataOrNull()?.cleanupCount() ?: 0) > 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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