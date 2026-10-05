package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.GetWorktreeUseCase
import dev.app.leaf.domain.usecases.OpenPathInSystemUseCase
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import kotlinx.coroutines.launch
import javax.inject.Inject

class QuickActionsViewModel @Inject constructor(
    private val refreshDataUseCase: RefreshDataUseCase,
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val openPathInSystemUseCase: OpenPathInSystemUseCase,
) : TabViewModel() {

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