package dev.app.leaf.domain.models

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