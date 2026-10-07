package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.CommitChangesSectionSizes
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import org.eclipse.jgit.diff.DiffEntry

data class CommitChangesState(
    val isLoading: Boolean,
    val error: AppError? = null,
    val commit: Commit,
    val viewState: FilesViewState = FilesViewState(),
    val showSearch: Boolean,
    val searchFilter: TextFieldValue,
    val changes: List<DiffEntry> = emptyList(),
    /** The visible rows for [viewState], already filtered by the search. */
    val rows: List<FileRow<DiffEntry>> = emptyList(),
    val sectionSizes: CommitChangesSectionSizes = CommitChangesSectionSizes(),
)
