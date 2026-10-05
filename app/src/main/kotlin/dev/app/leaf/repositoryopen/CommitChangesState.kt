package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.ui.tree_files.TreeItem
import org.eclipse.jgit.diff.DiffEntry

data class CommitChangesState(
    val isLoading: Boolean,
    val error: AppError? = null,
    val commit: Commit,
    val showAsTree: Boolean,
    val showSearch: Boolean,
    val searchFilter: TextFieldValue,
    val treeContractedDirectories: List<String> = emptyList(),
    val changes: List<DiffEntry> = emptyList(),
    val changesFiltered: List<DiffEntry> = emptyList(),
    val changesTree: List<TreeItem<DiffEntry>> = emptyList(),
    val changesTreeFiltered: List<TreeItem<DiffEntry>> = emptyList(),
)
