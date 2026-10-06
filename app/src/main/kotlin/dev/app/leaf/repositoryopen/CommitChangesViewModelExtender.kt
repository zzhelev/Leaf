package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.collectLatestInCoroutineScope
import dev.app.leaf.common.flows.combine
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.errOrNull
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.extensions.lowercaseContains
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.sorting.FileChangeKind
import dev.app.leaf.domain.sorting.FileItem
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.extensions.stateIn
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry
import kotlin.time.Duration.Companion.milliseconds

class CommitChangesViewModelExtender @AssistedInject constructor(
    private val getCommitDiffEntriesUseCase: GetCommitDiffEntriesUseCase,
    @Assisted private val viewModelScope: CoroutineScope,
    @Assisted private val filesViewState: StateFlow<FilesViewState>,
    @Assisted private val selectedItem: StateFlow<SelectedItem>,
    @Assisted private val diffSelected: StateFlow<DiffSelected?>,
    @Assisted private val onDiffSelected: (DiffSelected) -> Unit,
    @Assisted private val onViewStateChanged: (FilesViewState) -> Unit,
    @Assisted("addCloseableView") private val addCloseableView: (CloseableView) -> Unit,
    @Assisted("removeCloseableView") private val removeCloseableView: (CloseableView) -> Unit,
) : CoroutineScope by viewModelScope {

    @AssistedFactory
    interface Factory {
        fun create(
            viewModelScope: CoroutineScope,
            filesViewState: StateFlow<FilesViewState>,
            selectedItem: StateFlow<SelectedItem>,
            diffSelected: StateFlow<DiffSelected?>,
            onDiffSelected: (DiffSelected) -> Unit,
            onViewStateChanged: (FilesViewState) -> Unit,
            @Assisted("addCloseableView") addCloseableView: (CloseableView) -> Unit,
            @Assisted("removeCloseableView") removeCloseableView: (CloseableView) -> Unit,
        ): CommitChangesViewModelExtender
    }

    val showSearch: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val searchFilter: StateFlow<TextFieldValue>
        field = MutableStateFlow(TextFieldValue(""))

    /** Folders closed in the folder tree, by full path. Reset when another commit is selected. */
    private val collapsedDirectories = MutableStateFlow(emptySet<String>())

    /** Folders closed during the current search. Every folder with matches is open unless listed here. */
    private val searchCollapsedDirectories = MutableStateFlow(emptySet<String>())

    // Preserve the last loaded changes (from the previously selected commit) to prevent the UI from flickering while loading the data
    private val lastLoadedCommitsChanges = MutableStateFlow<List<DiffEntry>>(emptyList())

    private val isSearching = combine(showSearch, searchFilter) { showSearch, searchFilter ->
        showSearch && searchFilter.text.isNotBlank()
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val commitChangesState = combine(
        selectedItem.flatMapLatest { item -> loadCommitChangesFlow(item) },
        filesViewState,
        collapsedDirectories,
        searchCollapsedDirectories,
        showSearch,
        searchFilter,
    ) { state, viewState, collapsed, searchCollapsed, showSearch, searchFilter ->
        if (state == null) return@combine null

        val searching = showSearch && searchFilter.text.isNotBlank()
        val visibleChanges = if (searching) {
            state.changes.filter { it.filePath.lowercaseContains(searchFilter.text) }
        } else {
            state.changes
        }

        state.copy(
            viewState = viewState,
            showSearch = showSearch,
            searchFilter = searchFilter,
            rows = buildFileRows(
                files = visibleChanges.toFileItems(),
                state = viewState,
                isCollapsed = if (searching) searchCollapsed::contains else collapsed::contains,
            ),
        )
    }
        .onEach {
            if (it != null && !it.isLoading && it.changes.isNotEmpty()) {
                lastLoadedCommitsChanges.value = it.changes
            }
        }
        .stateIn(null as CommitChangesState?)

    private fun loadCommitChangesFlow(item: SelectedItem) = channelFlow {
        if (item is SelectedItem.CommitBasedItem) {
            send(
                CommitChangesState(
                    isLoading = false,
                    commit = item.commit,
                    showSearch = false,
                    searchFilter = TextFieldValue(""),
                    changes = lastLoadedCommitsChanges.value
                )
            )

            val changesResultDeferred = async { getCommitDiffEntriesUseCase(item.commit) }

            val loadingJob = launch {
                delay(150.milliseconds)

                if (!changesResultDeferred.isCompleted) {
                    send(
                        CommitChangesState(
                            isLoading = true,
                            commit = item.commit,
                            showSearch = false,
                            searchFilter = TextFieldValue(""),
                        )
                    )
                }
            }

            val changesResult: Either<List<DiffEntry>, AppError> = changesResultDeferred.await()
            loadingJob.cancel()

            val error = changesResult.errOrNull()
            val changes = changesResult.okOrNull()

            val state = CommitChangesState(
                commit = item.commit,
                changes = changes.orEmpty(),
                showSearch = false,
                searchFilter = TextFieldValue(""),
                error = error,
                isLoading = false,
            )

            send(state)
        } else {
            send(null)
        }
    }

    init {
        showSearch.collectLatestInCoroutineScope {
            if (it) {
                addSearchToCloseableView()
            } else {
                removeCommitChangesSearchFromCloseableView()
            }
        }

        selectedItem
            .map { (it as? SelectedItem.CommitBasedItem)?.commit?.hash }
            .distinctUntilChanged()
            .collectLatestInCoroutineScope { collapsedDirectories.value = emptySet() }

        isSearching.collectLatestInCoroutineScope { searchCollapsedDirectories.value = emptySet() }
    }

    fun onAction(action: CommitChangesAction) {
        when (action) {
            is CommitChangesAction.SearchFilterChanged -> searchFilter.value = action.filter
            is CommitChangesAction.SearchFilterToggle -> showSearch.value = action.show
            is CommitChangesAction.SelectEntry -> {
                onDiffSelected(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(action.entry))))
            }

            is CommitChangesAction.ViewStateChanged -> onViewStateChanged(action.viewState)

            is CommitChangesAction.TreeDirectoryToggle -> onDirectoryVisibilityToggle(action.path)
            CommitChangesAction.AddSearchToCloseables -> addSearchToCloseableView()
        }
    }

    private fun addSearchToCloseableView() = viewModelScope.launch {
        addCloseableView(CloseableView.COMMIT_CHANGES_SEARCH)
    }


    private fun removeCommitChangesSearchFromCloseableView() = viewModelScope.launch {
        removeCloseableView(CloseableView.COMMIT_CHANGES_SEARCH)
    }

    fun onDirectoryVisibilityToggle(directoryPath: String) {
        val searching = showSearch.value && searchFilter.value.text.isNotBlank()
        val directories = if (searching) searchCollapsedDirectories else collapsedDirectories

        directories.update { if (directoryPath in it) it - directoryPath else it + directoryPath }
    }

    fun searchFilterToggled(show: Boolean) {
        showSearch.value = show
        searchFilter.value = TextFieldValue("")
    }

}

private fun List<DiffEntry>.toFileItems(): List<FileItem<DiffEntry>> {
    val keyCounts = HashMap<String, Int>()

    return map { entry ->
        val path = entry.filePath
        val baseKey = "${entry.changeType}:$path"
        val count = keyCounts.merge(baseKey, 1, Int::plus) ?: 1

        FileItem(
            item = entry,
            key = if (count == 1) baseKey else "$baseKey#$count",
            path = path,
            kind = entry.changeKind,
        )
    }
}

private val DiffEntry.changeKind: FileChangeKind
    get() = when (changeType) {
        DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> FileChangeKind.Added
        DiffEntry.ChangeType.RENAME -> FileChangeKind.Renamed
        DiffEntry.ChangeType.DELETE -> FileChangeKind.Deleted
        else -> FileChangeKind.Modified
    }
