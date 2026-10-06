package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.sorting.FilesViewState
import org.eclipse.jgit.diff.DiffEntry

sealed interface CommitChangesAction {
    data class TreeDirectoryToggle(val path: String) : CommitChangesAction
    data class ViewStateChanged(val viewState: FilesViewState) : CommitChangesAction
    data class SelectEntry(val entry: DiffEntry) : CommitChangesAction
    data class SearchFilterChanged(val filter: TextFieldValue) : CommitChangesAction
    data class SearchFilterToggle(val show: Boolean) : CommitChangesAction
    data object AddSearchToCloseables : CommitChangesAction
}
