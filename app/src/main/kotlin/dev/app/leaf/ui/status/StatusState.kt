package dev.app.leaf.ui.status

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.common.flows.combine
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.extensions.lowercaseContains
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.sorting.inFolder
import dev.app.leaf.domain.sorting.toFileItems
import dev.app.leaf.ui.UiDataState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "StatusState"

sealed interface SelectionType<T> {
    data class SetSingleEntry<T>(val entry: T) : SelectionType<T>
    data class AppendSingleEntry<T>(val entry: T) : SelectionType<T>
    data class RemoveSingleEntry<T>(val entry: T) : SelectionType<T>
    data class AddMultipleEntries<T>(val entries: List<T>) : SelectionType<T>
}


@Immutable
data class StatusState(
    val isLoading: Boolean = true,
    /** Every staged entry, one per path. */
    val staged: List<StatusEntry> = emptyList(),
    /** Every unstaged entry, one per path, keeping the conflicting one when git reports a path twice. */
    val unstaged: List<StatusEntry> = emptyList(),
    /** The rows of the Staged and Unstaged panes, filtered by their searches. */
    val stagedRows: List<FileRow<StatusEntry>> = emptyList(),
    val unstagedRows: List<FileRow<StatusEntry>> = emptyList(),
    val swapUncommittedChanges: Boolean = false,
    val isAmend: Boolean = false,
    val isAmendRebaseInteractive: Boolean = false,
    val committerDataRequestState: CommitterDataRequestState = CommitterDataRequestState.None,
    val rebaseInteractiveState: RebaseInteractiveState = RebaseInteractiveState.None,
    val selectedUnstagedDiffEntries: List<DiffType.UncommittedDiff> = emptyList(),
    val selectedStagedDiffEntries: List<DiffType.UncommittedDiff> = emptyList(),
    val showSearchStaged: Boolean = false,
    val searchFilterStaged: TextFieldValue = TextFieldValue(""),
    val showSearchUnstaged: Boolean = false,
    val searchFilterUnstaged: TextFieldValue = TextFieldValue(""),
    val viewState: FilesViewState = FilesViewState(),
    val commitMessage: TextFieldValue = TextFieldValue(""),
    val previousCommitMessage: String? = null,
    val repositoryState: RepositoryState = RepositoryState.SAFE,
    val repositoryPath: String? = null,
) {
    val hasPreviousCommits: Boolean = previousCommitMessage != null

    val haveConflictsBeenSolved: Boolean = unstaged.none { it.statusType == StatusType.CONFLICTING }

    fun getEntriesByEntryType(entryType: EntryType): List<StatusEntry> {
        return when (entryType) {
            EntryType.STAGED -> staged
            EntryType.UNSTAGED -> unstaged
        }
    }

    /**
     * The files a folder row stands for: every file under [folderPath], including those inside closed folders, or only
     * the search matches while the pane is searching.
     */
    fun entriesShownUnder(folderPath: String, entryType: EntryType): List<StatusEntry> {
        val searchFilter = when (entryType) {
            EntryType.STAGED -> activeSearch(showSearchStaged, searchFilterStaged)
            EntryType.UNSTAGED -> activeSearch(showSearchUnstaged, searchFilterUnstaged)
        }

        return getEntriesByEntryType(entryType)
            .filteredBySearch(searchFilter)
            .inFolder(folderPath)
    }

    val hasStagedFiles = staged.isNotEmpty()
    val hasUnstagedFiles = unstaged.isNotEmpty()
}

fun combineStatusState(
    status: Flow<UiDataState<Status>>,
    showSearchStaged: MutableStateFlow<Boolean>,
    searchFilterStaged: MutableStateFlow<TextFieldValue>,
    showSearchUnstaged: MutableStateFlow<Boolean>,
    searchFilterUnstaged: MutableStateFlow<TextFieldValue>,
    viewState: Flow<FilesViewState>,
    stagedCollapsedFolders: Flow<CollapsedFolders>,
    unstagedCollapsedFolders: Flow<CollapsedFolders>,
    swapUncommittedChanges: Flow<Boolean>,
    isAmend: Flow<Boolean>,
    isAmendRebaseInteractive: Flow<Boolean>,
    committerDataRequestState: Flow<CommitterDataRequestState>,
    rebaseInteractiveState: Flow<RebaseInteractiveState>,
    selectedUnstagedDiffEntries: Flow<List<DiffType.UncommittedDiff>>,
    selectedStagedDiffEntries: Flow<List<DiffType.UncommittedDiff>>,
    commitMessage: Flow<TextFieldValue>,
    previousCommitMessage: Flow<String?>,
    repositoryState: Flow<UiDataState<RepositoryState>>,
    repositoryPath: Flow<String?>,
): Flow<StatusState> {
    return combine(
        status,
        showSearchStaged,
        searchFilterStaged,
        showSearchUnstaged,
        searchFilterUnstaged,
        viewState,
        stagedCollapsedFolders,
        unstagedCollapsedFolders,
        swapUncommittedChanges,
        isAmend,
        isAmendRebaseInteractive,
        committerDataRequestState,
        rebaseInteractiveState,
        selectedUnstagedDiffEntries,
        selectedStagedDiffEntries,
        commitMessage,
        previousCommitMessage,
        repositoryState,
        repositoryPath,
    ) {
            statusDataState,
            showSearchStaged,
            searchFilterStaged,
            showSearchUnstaged,
            searchFilterUnstaged,
            viewState,
            stagedCollapsedFolders,
            unstagedCollapsedFolders,
            swapUncommittedChanges,
            isAmend,
            isAmendRebaseInteractive,
            committerDataRequestState,
            rebaseInteractiveState,
            selectedUnstagedDiffEntries,
            selectedStagedDiffEntries,
            commitMessage,
            previousCommitMessage,
            repositoryStateDateState,
            repositoryPath,
        ->
        val status = statusDataState.data ?: Status()
        val repositoryState = repositoryStateDateState.data ?: RepositoryState.SAFE
        val staged = status.staged.prioritizeConflicts()
        val unstaged = status.unstaged.prioritizeConflicts()

        val isLoading = statusDataState.isLoading || repositoryStateDateState.isLoading

        StatusState(
            isLoading = isLoading,
            staged = staged,
            unstaged = unstaged,
            stagedRows = statusPaneRows(
                staged,
                viewState,
                searchFilter = activeSearch(showSearchStaged, searchFilterStaged),
                stagedCollapsedFolders,
            ),
            unstagedRows = statusPaneRows(
                unstaged,
                viewState,
                searchFilter = activeSearch(showSearchUnstaged, searchFilterUnstaged),
                unstagedCollapsedFolders,
            ),
            swapUncommittedChanges = swapUncommittedChanges,
            isAmend = isAmend,
            isAmendRebaseInteractive = isAmendRebaseInteractive,
            committerDataRequestState = committerDataRequestState,
            rebaseInteractiveState = rebaseInteractiveState,
            selectedUnstagedDiffEntries = selectedUnstagedDiffEntries,
            selectedStagedDiffEntries = selectedStagedDiffEntries,
            showSearchStaged = showSearchStaged,
            searchFilterStaged = searchFilterStaged,
            showSearchUnstaged = showSearchUnstaged,
            searchFilterUnstaged = searchFilterUnstaged,
            viewState = viewState,
            commitMessage = commitMessage,
            previousCommitMessage = previousCommitMessage,
            repositoryState = repositoryState,
            repositoryPath = repositoryPath,
        )
    }
}

/** The rows of one pane. [searchFilter] is the search text, or null when the pane isn't searching. */
private fun statusPaneRows(
    entries: List<StatusEntry>,
    viewState: FilesViewState,
    searchFilter: String?,
    collapsedFolders: CollapsedFolders,
): List<FileRow<StatusEntry>> {
    val isSearching = searchFilter != null

    return buildFileRows(entries.filteredBySearch(searchFilter).toFileItems(), viewState) { path ->
        collapsedFolders.isCollapsed(path, isSearching)
    }
}

/** A pane's search text, or null when the pane isn't searching. */
private fun activeSearch(showSearch: Boolean, searchFilter: TextFieldValue): String? =
    searchFilter.text.takeIf { showSearch && it.isNotBlank() }

private fun List<StatusEntry>.filteredBySearch(searchFilter: String?): List<StatusEntry> =
    if (searchFilter == null) this else filter { it.filePath.lowercaseContains(searchFilter) }

private fun List<StatusEntry>.prioritizeConflicts(): List<StatusEntry> {
    return this.groupBy { it.filePath }
        .map {
            val statusEntries = it.value
            return@map if (statusEntries.count() == 1) {
                statusEntries.first()
            } else {
                val conflictingEntry =
                    statusEntries.firstOrNull { entry -> entry.statusType == StatusType.CONFLICTING }

                conflictingEntry ?: statusEntries.first()
            }
        }
}

sealed interface CommitterDataRequestState {
    data object None : CommitterDataRequestState
    data class WaitingInput(val authorInfo: AuthorInfo) : CommitterDataRequestState
    data class Accepted(val authorInfo: AuthorInfo, val persist: Boolean) : CommitterDataRequestState
    object Reject : CommitterDataRequestState
}
