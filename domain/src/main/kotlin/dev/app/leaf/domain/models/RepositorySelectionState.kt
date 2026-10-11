package dev.app.leaf.domain.models

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface RepositorySelectionState {
    data object Unknown : RepositorySelectionState
    data object None : RepositorySelectionState
    data class Opening(val path: String) : RepositorySelectionState
    data class Open(val path: String) : RepositorySelectionState
}

/**
 * The path a tab in this state is reopened from on the next launch, or null if the tab isn't saved. [initialPath] is
 * the path the tab was created with. Tabs load when they're first shown, so restored tabs stay `Unknown` until then.
 */
fun RepositorySelectionState.pathToPersist(initialPath: String?): String? = when (this) {
    RepositorySelectionState.None -> null
    is RepositorySelectionState.Open -> path
    is RepositorySelectionState.Opening -> path
    RepositorySelectionState.Unknown -> initialPath
}
/**
 * The git dirs of the worktrees that the tab switches to (fork-only): each `Open` state that follows an `Open` state
 * with another git dir. Opening the tab's first repository isn't a switch.
 */
fun Flow<RepositorySelectionState>.worktreeSwitches(): Flow<String> = flow {
    var openGitDir: String? = null

    collect { state ->
        if (state is RepositorySelectionState.Open) {
            if (openGitDir != null && openGitDir != state.path) {
                emit(state.path)
            }

            openGitDir = state.path
        }
    }
}
