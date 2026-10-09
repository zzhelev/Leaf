package dev.app.leaf.repositoryopen

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import dev.app.leaf.TabViewModel
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.merge_automatic_stash_description
import dev.app.leaf.app.generated.resources.pull_with_merge_automatic_stash_description
import dev.app.leaf.app.generated.resources.pull_with_merge_from_specific_branch_automatic_stash_description
import dev.app.leaf.collectLatestInViewModel
import dev.app.leaf.common.flows.invert
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.AppStateManager
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.exceptions.InvalidMessageException
import dev.app.leaf.domain.extensions.lowercaseContains
import dev.app.leaf.domain.extensions.openFileInFolder
import dev.app.leaf.domain.extensions.toMutableSetAndAdd
import dev.app.leaf.domain.extensions.toMutableSetAndRemove
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.*
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RefFolderExpansion
import dev.app.leaf.domain.sorting.RefPanelSettings
import dev.app.leaf.domain.sorting.RefSection
import dev.app.leaf.domain.sorting.RefSortState
import dev.app.leaf.domain.usecases.*
import dev.app.leaf.extensions.stateIn
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.system.OpenUrlInBrowserUseCase
import dev.app.leaf.system.PickerType
import dev.app.leaf.terminal.OpenRepositoryInTerminalGitAction
import dev.app.leaf.ui.AppViewModel
import dev.app.leaf.ui.IVerticalSplitPaneConfig
import dev.app.leaf.ui.VerticalSplitPaneConfig
import dev.app.leaf.ui.dialogs.FastForwardOffer
import dev.app.leaf.ui.status.StatusAction
import dev.app.leaf.ui.toUiDataState
import dev.app.leaf.updates.Update
import dev.app.leaf.updates.UpdatesRepository
import dev.app.leaf.viewmodels.HistoryViewModel
import dev.app.leaf.viewmodels.sidepanel.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.jgit.api.RebaseCommand.InteractiveHandler
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.RebaseTodoLine
import org.jetbrains.compose.resources.getString
import java.io.File
import javax.inject.Inject
import javax.inject.Provider


private const val MIN_TIME_IN_MS_TO_SHOW_LOAD = 500L


private const val MIN_TIME_AFTER_GIT_OPERATION = 2000L
private const val INCREMENTAL_COMMITS_LOAD = 500


/**
 * Represents when the search filter is not being used or the results list is empty
 */
private const val NONE_MATCHING_INDEX = 0

/**
 * The search UI starts the index count at 1 (for example "1/10" to represent the first commit of the search result
 * being selected)
 */
private const val FIRST_INDEX = 1

private const val TAG = "TabViewModel"

/**
 * Contains all the information related to a tab and its subcomponents (smaller composables like the log, branches,
 * commit changes, etc.). It holds a reference to every view model because this class lives as long as the tab is open (survives
 * across full app recompositions), therefore, tab's content can be recreated with these view models.
 */
class RepositoryOpenViewModel @Inject constructor(
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val historyViewModelProvider: Provider<HistoryViewModel>,
    val appStateManager: AppStateManager,
    private val openFilePickerUseCase: OpenFilePickerUseCase,
    private val openUrlInBrowserUseCase: OpenUrlInBrowserUseCase,
    private val appViewModel: AppViewModel,
    private val tabScope: TabCoroutineScope,
    private val verticalSplitPaneConfig: VerticalSplitPaneConfig,
    private val refreshDataUseCase: RefreshDataUseCase,
    private val increaseLogCountUseCase: IncreaseLogCountUseCase,
    private val blameFileUseCase: BlameFileUseCase,
    updatesRepository: UpdatesRepository,
    private val fetchRemotesUseCase: FetchRemotesUseCase,
    private val deleteRemoteInfoUseCase: DeleteRemoteInfoUseCase,
    private val checkoutCommitUseCase: CheckoutCommitUseCase,
    private val rebaseBranchUseCase: RebaseBranchUseCase,
    private val deleteRemoteBranchUseCase: DeleteRemoteBranchUseCase,
    private val deleteSubmoduleUseCase: DeleteSubmoduleUseCase,
    private val mergeBranchUseCase: MergeBranchUseCase,
    private val checkoutBranchUseCase: CheckoutBranchUseCase,
    private val getRemoteBranchCheckoutUseCase: GetRemoteBranchCheckoutUseCase,
    private val checkoutRemoteBranchUseCase: CheckoutRemoteBranchUseCase,
    private val updateSubmoduleUseCase: UpdateSubmoduleUseCase,
    private val syncSubmoduleUseCase: SyncSubmoduleUseCase,
    private val pushBranchUseCase: PushBranchUseCase,
    private val pullBranchUseCase: PullBranchUseCase,
    private val initializeSubmoduleUseCase: InitializeSubmoduleUseCase,
    private val applyStashUseCase: ApplyStashUseCase,
    private val popStashUseCase: PopStashUseCase,
    private val deleteStashUseCase: DeleteStashUseCase,
    private val startRebaseInteractiveUseCase: StartRebaseInteractiveUseCase,
    private val cherryPickCommitUseCase: CherryPickCommitUseCase,
    private val revertCommitUseCase: RevertCommitUseCase,
    private val abortRebaseUseCase: AbortRebaseUseCase,
    private val appSettings: AppSettingsService,
    private val addSelectedDiffUseCase: AddSelectedDiffUseCase,
    private val getSummaryFromStatusUseCase: GetSummaryFromStatusUseCase,
    private val stageHunkUseCase: StageHunkUseCase,
    private val unstageHunkUseCase: UnstageHunkUseCase,
    private val stageHunkLineUseCase: StageHunkLineUseCase,
    private val unstageHunkLineUseCase: UnstageHunkLineUseCase,
    private val resetHunkUseCase: ResetHunkUseCase,
    private val discardHunkLineUseCase: DiscardHunkLineUseCase,
    private val statusStageUseCase: StatusStageUseCase,
    private val statusUnstageUseCase: StatusUnstageUseCase,
    private val openFileInExternalAppUseCase: OpenFileInExternalAppUseCase,
    private val getDiffUseCase: GetDiffUseCase,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val settings: AppSettingsService,
    private val getCommitFromRebaseLineUseCase: GetCommitFromRebaseLineUseCase,
    private val resumeRebaseInteractiveUseCase: ResumeRebaseInteractiveUseCase,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val statusViewModelExtenderFactory: StatusViewModelExtender.Factory,
    private val commitChangesViewModelExtenderFactory: CommitChangesViewModelExtender.Factory,
    private val getCommitFromHashUseCase: GetCommitFromHashUseCase,
    private val fetchAllUseCase: FetchAllBranchUseCase,
    private val stashChangesUseCase: StashChangesUseCase,
    private val openRepositoryInTerminalGitAction: OpenRepositoryInTerminalGitAction,
    private val loadRefFolderExpansionUseCase: LoadRefFolderExpansionUseCase,
    private val saveRefFolderExpansionUseCase: SaveRefFolderExpansionUseCase,
) : IVerticalSplitPaneConfig by verticalSplitPaneConfig,
    TabViewModel() {
    val completedTasks = repositoryStateRepository.completedTasks

    val isPullWithRebaseDefault = settings.pullWithRebase

    val lastLoadedTabs = appStateManager.latestOpenedRepositoriesPaths

    val repositoryState: StateFlow<RepositoryState> =
        repositoryDataRepository.repositoryState.toUiDataState()
            .map { it.data ?: RepositoryState.SAFE } // TODO: Instead of using safe as default, it should show some kind of feedback while data is null
            .stateIn(RepositoryState.SAFE)

    val rebaseInteractiveState = repositoryDataRepository
        .rebaseInteractiveState
        .map {
            if (it is DataState.Loaded) {
                it.data
            } else {
                RebaseInteractiveState.None
            }
        }
        .mutableStateIn(viewModelScope, RebaseInteractiveState.None)

    val filter: StateFlow<String>
        field = MutableStateFlow("")

    val selectedItem: StateFlow<SelectedItem>
        field = MutableStateFlow<SelectedItem>(SelectedItem.UncommittedChanges)

    val isExpandedBranches: StateFlow<Boolean>
        field = MutableStateFlow<Boolean>(true)

    val isExpandedRemotes: StateFlow<Boolean>
        field = MutableStateFlow<Boolean>(false)

    val isExpandedStashes: StateFlow<Boolean>
        field = MutableStateFlow<Boolean>(true)

    val isExpandedTags: StateFlow<Boolean>
        field = MutableStateFlow<Boolean>(false)

    val isExpandedSubmodules: StateFlow<Boolean>
        field = MutableStateFlow<Boolean>(true)

    val freeSearchFocusFlow: SharedFlow<Unit>
        field = MutableSharedFlow<Unit>()

    /** Remote branches being checked out whose local branch can be fast-forwarded first, if the user wants to. */
    val fastForwardOffers: SharedFlow<FastForwardOffer>
        field = MutableSharedFlow<FastForwardOffer>()

    val diffSelected: StateFlow<DiffSelected?>
        field = MutableStateFlow<DiffSelected?>(null)

    private val closeableViews = ArrayDeque<CloseableView>()
    private val closeableViewsMutex = Mutex()

    private val _closeView = MutableSharedFlow<CloseableView>()
    val closeViewFlow = _closeView.asSharedFlow()

    fun addCloseableView(view: CloseableView) {
        viewModelScope.launch {
            closeableViewsMutex.withLock {
                closeableViews.remove(view) // Remove any previous elements if present
                closeableViews.add(view)
            }
        }
    }

    fun removeCloseableView(view: CloseableView) {
        viewModelScope.launch {
            closeableViewsMutex.withLock {
                closeableViews.remove(view)
            }
        }
    }

    fun closeLastView() {
        viewModelScope.launch {
            closeableViewsMutex.withLock {
                val last = closeableViews.removeLastOrNull()

                if (last != null) {
                    _closeView.emit(last)
                }
            }
        }
    }

    private val branches = repositoryDataRepository.localBranches.toUiDataState()
    private val currentBranch = repositoryDataRepository
        .currentBranch
        .toUiDataState()

    private val remotes = repositoryDataRepository.remotes.toUiDataState()

    private val logBranchesByCommitHash =
        combine(branches, remotes, currentBranch) { branches, remotes, currentBranch ->
            val branches = branches.data.orEmpty()
            val remotes = remotes.data.orEmpty()
            val currentBranch = currentBranch.data

            (branches + remotes.flatMap { it.branchesList })
                .filter { branch ->
                    currentBranch?.name == "HEAD" || branch.simpleName != "HEAD"
                }
                .groupBy { branch -> branch.hash }
        }
            .distinctUntilChanged()

    private val tagsByCommitHash = repositoryDataRepository.tags.map {
        if (it is DataState.Loaded) {
            it.data.groupBy { tag -> tag.commitHash }
        } else {
            emptyMap()
        }
    }

    private val stashesHashes = repositoryDataRepository
        .stashes
        .toUiDataState()
        .map {
            it
                .data
                .orEmpty()
                .map { commit ->
                    commit.hash
                }
                .toHashSet()
        }

    val refPanelSettings: StateFlow<RefPanelSettings> = appSettings.refPanelSettings
        .stateIn(RefPanelSettings())

    private val refPanelSettingsMutex = Mutex()

    /** Shared by every tab, like the side panel's sort settings. */
    val logColumns: StateFlow<LogColumnsSettings> = appSettings.logColumns
        .stateIn(LogColumnsSettings())

    private val logColumnsMutex = Mutex()

    private val refFolderExpansion = MutableStateFlow(RefFolderExpansion())
    private val refFolderExpansionSaveMutex = Mutex()

    /** Folders closed during the current search. Cleared when a search starts or ends. */
    private val searchCollapsedFolders = MutableStateFlow<Set<String>>(emptySet())

    private val refRowsContext = combine(
        refPanelSettings,
        repositoryDataRepository.refDates.toUiDataState(),
        refFolderExpansion,
        searchCollapsedFolders,
    ) { settings, refDates, expansion, searchCollapsed ->
        RefRowsContext(settings, refDates.data ?: RefDates(), expansion, searchCollapsed)
    }
        .distinctUntilChanged()

    val branchesState =
        combineBranchesState(branches, currentBranch, isExpandedBranches, filter, refRowsContext)
            .stateIn(BranchesState(isLoading = true, emptyList(), isExpandedBranches.value, null))

    private val remotesContracted = MutableStateFlow<Set<Remote>>(emptySet())
    val remoteState: StateFlow<RemotesState> =
        combineRemotesState(
            remotes,
            isExpandedRemotes,
            filter,
            currentBranch,
            remotesContracted,
            refRowsContext,
        ).stateIn(RemotesState())

    val stashesState: StateFlow<StashesState> =
        combine(
            repositoryDataRepository.stashes.toUiDataState(),
            isExpandedStashes,
            filter
        ) { stashes, isExpanded, filter ->
            StashesState(
                stashes = stashes.data.orEmpty().filter { it.message.lowercaseContains(filter) },
                isExpanded,
            )
        }.stateIn(StashesState(emptyList(), isExpandedStashes.value))

    val tagsState: StateFlow<TagsState> =
        combine(
            repositoryDataRepository.tags.toUiDataState(),
            isExpandedTags,
            filter,
            refRowsContext,
        ) { tags, isExpanded, filter, rowsContext ->
            val tagsFiltered = tags.data.orEmpty().filter { tag -> tag.simpleName.lowercaseContains(filter) }

            TagsState(
                tagsFiltered,
                isExpanded,
                rows = tagRows(tagsFiltered, rowsContext, isSearching = filter.isNotBlank(), System.currentTimeMillis()),
                sortState = rowsContext.settings.sortOf(RefSection.Tags),
            )
        }.stateIn(TagsState(emptyList(), isExpandedTags.value))


    val submodulesState: StateFlow<SubmodulesState> =
        combine(
            repositoryDataRepository.submodules.toUiDataState(),
            isExpandedSubmodules,
            filter
        ) { submodules, isExpanded, filter ->
            SubmodulesState(
                isLoading = submodules.isLoading,
                submodules = submodules.data.orEmpty().filter { it.key.lowercaseContains(filter) }.toList(),
                isExpanded = isExpanded
            )
        }.stateIn(SubmodulesState(isLoading = true, emptyList(), isExpandedSubmodules.value))

    val hasUncommittedChanges = repositoryDataRepository
        .status
        .toUiDataState()
        .map { data ->
            val status = data.data ?: Status()

            status.staged.isNotEmpty() || status.unstaged.isNotEmpty()
        }
        .stateIn(false)

    private val log = repositoryDataRepository.log.toUiDataState()
    private val statusSummary = repositoryDataRepository
        .status
        .toUiDataState()
        .map {
            val status = it.data ?: Status()
            getSummaryFromStatusUseCase(status)
        }

    private val verticalListState = MutableStateFlow(LazyListState(0, 0))
    private val horizontalListState = MutableStateFlow(ScrollState(0))


    val logSearchFilterResults: StateFlow<LogSearch>
        field = MutableStateFlow<LogSearch>(LogSearch.NotSearching)

    val logState = combineLogState(
        log,
        hasUncommittedChanges,
        currentBranch,
        branches = logBranchesByCommitHash,
        tags = tagsByCommitHash,
        stashes = stashesHashes,
        statusSummary,
        logSearchFilterResults,
        verticalListState,
        horizontalListState,
    )
        .stateIn(LogState(true))


    /** How Files changed, Staged and Unstaged sort and show files. They share one setting. */
    private val filesViewState = appSettings.filesChangedView.stateIn(FilesViewState())

    private val statusViewModelExtender = statusViewModelExtenderFactory.create(
        viewModelScope,
        filesViewState,
        diffSelected,
        rebaseInteractiveState,
        onOpenFileInFolder = ::openFileInFolder,
        onDiffSelected = {
            diffSelected.value = it
        },
        onRemoveEntriesFromSelection = { entries, entryType ->
            removeSelectedDiff(entries, entryType)
        },
        onViewStateChanged = ::setFilesChangedView,
        addCloseableView = ::addCloseableView,
        removeCloseableView = ::removeCloseableView,
    )
    private val commitChangesViewModelExtender = commitChangesViewModelExtenderFactory.create(
        viewModelScope,
        filesViewState,
        selectedItem,
        diffSelected,
        onDiffSelected = {
            diffSelected.value = it
        },
        onViewStateChanged = ::setFilesChangedView,
        addCloseableView = ::addCloseableView,
        removeCloseableView = ::removeCloseableView,
    )

    val commitChangesState = commitChangesViewModelExtender.commitChangesState

    val statusState = statusViewModelExtender.statusState

    init {
        closeViewFlow.collectLatestInViewModel {
            when (it) {
                CloseableView.SIDE_PANE_SEARCH -> {
                    newFilter("")
                    freeSearchFocusFlow.emit(Unit)
                }

                CloseableView.LOG_SEARCH -> {
                    logSearchFilterResults.value = LogSearch.NotSearching
                }

                CloseableView.STAGED_CHANGES_SEARCH -> {
                    statusViewModelExtender.searchFilterToggledStaged(false)
                }

                CloseableView.UNSTAGED_CHANGES_SEARCH -> {
                    statusViewModelExtender.searchFilterToggledUnstaged(false)
                }

                CloseableView.DIFF -> {
                }

                CloseableView.COMMIT_CHANGES_SEARCH -> commitChangesViewModelExtender.searchFilterToggled(false)

            }
        }

        tabScope.run {
            launch {
                //watchRepositoryChanges(tabState.git)
            }
        }

        diffSelected.collectLatestInViewModel {
            if (it != null && it.entries.count() == 1) {
                minimizeBlame()
            }
        }

        logSearchFilterResults.collectLatestInViewModel {
            when (it) {
                LogSearch.NotSearching -> removeSearchFromCloseableView()
                is LogSearch.SearchResults -> addSearchToCloseableView()
            }
        }

        repositoryDataRepository.repositorySelectionState.collectLatestInViewModel { state ->
            if (state is RepositorySelectionState.Open) {
                loadRefFolderExpansionUseCase().okOrNull()?.let { refFolderExpansion.value = it }
            }
        }
    }

    fun newFilter(newValue: String) {
        if (filter.value.isBlank() != newValue.isBlank()) {
            // Folders closed during a search open again for the next one, and the saved state comes back after it
            searchCollapsedFolders.value = emptySet()
        }

        filter.value = newValue
    }

    fun addSidePanelSearchToCloseables() = tabScope.launch {
        addCloseableView(CloseableView.SIDE_PANE_SEARCH)
    }

    fun removeSidePanelSearchFromCloseables() = tabScope.launch {
        removeCloseableView(CloseableView.SIDE_PANE_SEARCH)
    }

    fun onExpandBranches() {
        isExpandedBranches.invert()
    }

    fun onExpandRemotes() {
        isExpandedRemotes.invert()
    }

    fun onExpandSubmodules() {
        isExpandedSubmodules.invert()
    }

    fun onExpandStashes() {
        isExpandedStashes.invert()
    }

    fun onExpandTags() {
        isExpandedTags.invert()
    }


    fun onRefSortChanged(section: RefSection, sortState: RefSortState) = updateRefPanelSettings {
        it.withSort(section, sortState)
    }

    fun onKeepHeadOnTopToggled() = updateRefPanelSettings { it.copy(keepHeadOnTop = !it.keepHeadOnTop) }

    fun onGroupByPrefixToggled() = updateRefPanelSettings { it.copy(groupByPrefix = !it.groupByPrefix) }

    private fun updateRefPanelSettings(update: (RefPanelSettings) -> RefPanelSettings) = tabScope.launch {
        refPanelSettingsMutex.withLock {
            appSettings.setConfiguration(AppConfig.RefPanel(update(appSettings.refPanelSettings.first())))
        }
    }

    private fun updateLogColumns(update: (LogColumnsSettings) -> LogColumnsSettings) = tabScope.launch {
        logColumnsMutex.withLock {
            appSettings.setConfiguration(AppConfig.LogColumns(update(appSettings.logColumns.first())))
        }
    }

    fun onRefFolderToggled(key: String) {
        if (filter.value.isNotBlank()) {
            searchCollapsedFolders.update { if (key in it) it - key else it + key }
            return
        }

        val isDefaultExpanded = key == headFolderKey(branchesState.value.currentBranch)
        refFolderExpansion.update { it.toggled(key, isDefaultExpanded) }

        tabScope.launch {
            refFolderExpansionSaveMutex.withLock {
                val result = saveRefFolderExpansionUseCase(refFolderExpansion.value)

                if (result is Either.Err) {
                    printError(TAG, "Failed to save the side panel folders: ${result.error}")
                }
            }
        }
    }

    fun onRemoteClicked(remoteClicked: RemoteView) {
        remotesContracted.value = if (remotesContracted.value.contains(remoteClicked.remoteInfo.remote)) {
            remotesContracted.value.toMutableSetAndRemove(remoteClicked.remoteInfo.remote)
        } else {
            remotesContracted.value.toMutableSetAndAdd(remoteClicked.remoteInfo.remote)
        }
    }

    fun selectBranch(branch: Branch) = viewModelScope.launch {
        val commit = getCommitFromHashUseCase(branch.hash).okOrNull()

        if (commit != null) {
            selectedItem.value = SelectedItem.BranchItem(branch, commit)
        }
    }

    fun deleteRemote(remoteInfo: RemoteInfo) = deleteRemoteInfoUseCase(remoteInfo)

    fun onFetchRemoteBranches(remote: RemoteView) = fetchRemotesUseCase(remote.remoteInfo.remote)

    fun checkoutTagCommit(tag: Tag) = checkoutCommitUseCase(tag.commitHash)

    fun selectTag(tag: Tag) = viewModelScope.launch {
        val commit = getCommitFromHashUseCase(tag.commitHash).okOrNull()

        if (commit != null) {
            selectedItem.value = SelectedItem.TagItem(tag, commit)
        }
    }

    fun onOpenSubmoduleInTab(path: String) = viewModelScope.launch {
        val repositoryPath = getWorktreeUseCase()

        if (repositoryPath is Either.Ok) {
            appViewModel.addNewTabFromPath("${repositoryPath.value}/$path", true)
        }
    }

    fun initializeSubmodule(path: String) = initializeSubmoduleUseCase(path)

    fun syncSubmodule(path: String) = syncSubmoduleUseCase(path)

    fun updateSubmodule(path: String) = updateSubmoduleUseCase(path)

    fun deleteSubmodule(path: String) = deleteSubmoduleUseCase(path)

    fun mergeBranch(branch: Branch) {
        viewModelScope.launch {
            mergeBranchUseCase(
                branch,
                automaticStashDescription = getString(
                    Res.string.merge_automatic_stash_description,
                    branch.simpleNameWithRemote,
                    currentBranch.value.data?.simpleName.orEmpty(),
                ),
            )
        }
    }

    fun checkoutBranch(branch: Branch) = checkoutBranchUseCase(branch)

    fun rebaseBranch(branch: Branch) = rebaseBranchUseCase(branch)

    fun deleteRemoteBranch(branch: Branch) = deleteRemoteBranchUseCase(branch)

    /**
     * Checks out the local branch of [remoteBranch], which is created if it doesn't exist. When it exists and is behind
     * [remoteBranch], an offer on [fastForwardOffers] lets the user choose whether to fast-forward it first.
     */
    fun checkoutRemoteBranch(remoteBranch: Branch) {
        viewModelScope.launch {
            // If this fails, the checkout fails too and reports why
            val checkout = getRemoteBranchCheckoutUseCase(remoteBranch).okOrNull()

            if (checkout is RemoteBranchCheckout.ChecksOutLocalBranch && checkout.canFastForward) {
                fastForwardOffers.emit(FastForwardOffer(remoteBranch, checkout))
            } else {
                checkoutRemoteBranchUseCase(remoteBranch, fastForward = false)
            }
        }
    }

    fun checkoutRemoteBranch(remoteBranch: Branch, fastForward: Boolean) =
        checkoutRemoteBranchUseCase(remoteBranch, fastForward)

    fun applyStash(stash: Commit) = applyStashUseCase(stash)
    fun popStash(stash: Commit) = popStashUseCase(stash)
    fun deleteStash(stash: Commit) = deleteStashUseCase(stash)

    fun pushToRemoteBranch(branch: Branch) = pushBranchUseCase(
        force = false,
        pushTags = false,
        targetRemoteBranch = branch
    )

    fun pull(pullType: PullType) = pullBranch(pullType)

    fun push(force: Boolean, pushTags: Boolean) = pushBranchUseCase(force, pushTags)

    fun fetchAll() = fetchAllUseCase()

    fun stash() = stashChangesUseCase(null)

    fun popStash() = popStashUseCase(null)

    fun openTerminal() {
        openRepositoryInTerminalGitAction()
    }

    fun pullFromRemoteBranch(branch: Branch) {
        pullBranch(PullType.DEFAULT, branch)
    }

    private fun pullBranch(pullType: PullType, remoteBranch: Branch? = null) {
        viewModelScope.launch {
            val currentBranch = currentBranch.value.data?.simpleName.orEmpty()

            val automaticStashDescription = if (remoteBranch != null) {
                getString(
                    Res.string.pull_with_merge_from_specific_branch_automatic_stash_description,
                    remoteBranch.simpleNameWithRemote,
                    currentBranch
                )
            } else {
                getString(Res.string.pull_with_merge_automatic_stash_description, currentBranch)
            }

            pullBranchUseCase(
                pullType,
                remoteBranch,
                automaticStashDescription = automaticStashDescription,
            )
        }
    }

    fun selectStash(stash: Commit) {
        selectCommit(stash)
    }

    private val _blameState = MutableStateFlow<BlameState>(BlameState.None)
    val blameState: StateFlow<BlameState> = _blameState

    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory

    val authorInfoSimple = repositoryDataRepository
        .author
        .map {
            when (it) {
                is DataState.Loaded -> {
                    val identity = it.data.identityToUse()
                    DataState.Loaded(identity)
                }

                is DataState.Loading -> DataState.Loading
                is DataState.Error -> DataState.Error(it.error)
            }
        }
        .toUiDataState()

    var historyViewModel: HistoryViewModel? = null
        private set

    private var hasGitDirChanged = false


    override fun onClear() {
    }

    /**
     * To make sure the tab opens the new repository with a clean state,
     * instead of opening the repo in the same ViewModel we simply create a new tab with a new TabViewModel
     * replacing the current tab
     */
    fun openAnotherRepository(directory: String) {
        viewModelScope.launch {
            val worktree = getWorktreeUseCase()

            if (worktree is Either.Ok) {
                appViewModel.addNewTabFromPath(directory, true, worktree.value)
            }
        }
    }


    private fun refreshRepositoryInfo() {
        refreshDataUseCase(DataToRefresh.ALL)
    }

    fun openDirectoryPicker(): String? {
        val latestDirectoryOpened = appStateManager.latestOpenedRepositoryPath

        return openFilePickerUseCase(PickerType.DIRECTORIES, latestDirectoryOpened)
    }

    val update: StateFlow<Update?> = updatesRepository.update

    fun blameFile(filePath: String) {
        viewModelScope.launch {
            _blameState.value = BlameState.Loading(filePath)

            when (val result = blameFileUseCase(filePath)) {
                is Either.Err -> {
                    resetBlameState()
                }

                is Either.Ok -> {
                    _blameState.value = BlameState.Loaded(filePath, result.value)
                }
            }
        }
    }

    fun resetBlameState() {
        _blameState.value = BlameState.None
    }

    fun expandBlame() {
        val blameState = _blameState.value

        if (blameState is BlameState.Loaded && blameState.isMinimized) {
            _blameState.value = blameState.copy(isMinimized = false)
        }
    }

    fun minimizeBlame() {
        val blameState = _blameState.value

        if (blameState is BlameState.Loaded && !blameState.isMinimized) {
            _blameState.value = blameState.copy(isMinimized = true)
        }
    }

    fun fileHistory(filePath: String) {
        historyViewModel = historyViewModelProvider.get()
        historyViewModel?.fileHistory(filePath)
        _showHistory.value = true
    }

    fun closeHistory() {
        _showHistory.value = false
        historyViewModel = null
    }

    fun refreshAll() = tabScope.launch {
        val currentTask = repositoryStateRepository.currentTask
        printLog(TAG, "Manual refresh triggered. Current task: $currentTask")

        if (repositoryStateRepository.currentTask.value == null) {
            refreshRepositoryInfo()
        }
    }

    fun openUrlInBrowser(url: String) {
        openUrlInBrowserUseCase(url)
    }

    var savedSearchFilter: String = ""

    private var lastIndexUsedToLoadData = 0
    private val loadItemsMutex = Mutex()

    // TODO Restore functionality after refactoring
    private val scrollToItem: Flow<GraphCommit> = emptyFlow() /*tabState.taskEvent
        .filterIsInstance<TaskEvent.ScrollToGraphItem>()
        .map { it.selectedItem }
        .filterIsInstance<SelectedItem.CommitBasedItem>()
        .map { it.revCommit }
*/
    val scrollToUncommittedChanges: Flow<SelectedItem.UncommittedChanges> = emptyFlow() /*tabState.taskEvent
        .filterIsInstance<TaskEvent.ScrollToGraphItem>()
        .map { it.selectedItem }
        .filterIsInstance()*/

    private val _focusCommit = MutableSharedFlow<GraphCommit>()
    val focusCommit: Flow<GraphCommit> = merge(_focusCommit, scrollToItem)


    fun onAction(action: StatusAction) {
        statusViewModelExtender.onAction(action)
    }

    fun onAction(action: CommitChangesAction) {
        commitChangesViewModelExtender.onAction(action)
    }

    fun onAction(action: LogAction) {
        when (action) {
            is LogAction.ApplyStash -> applyStash(action.commit)
            is LogAction.CheckoutCommit -> checkoutCommit(action.commit)
            is LogAction.CheckoutBranch -> checkoutBranch(action.branch)
            is LogAction.CheckoutRemoteBranch -> checkoutRemoteBranch(action.branch)
            is LogAction.CheckoutTag -> checkoutTag(action.tag)
            is LogAction.CherryPickCommit -> cherryPickCommit(action.commit)
            is LogAction.CommitSelected -> selectCommit(action.commit)
            is LogAction.DeleteRemoteBranch -> deleteRemoteBranch(action.branch)
            is LogAction.DeleteStash -> deleteStash(action.commit)
            is LogAction.Merge -> mergeBranch(action.branch)
            is LogAction.PopStash -> popStash(action.commit)
            is LogAction.PullFromRemoteBranch -> pullFromRemoteBranch(action.branch)
            is LogAction.PushToRemoteBranch -> pushToRemoteBranch(action.branch)
            is LogAction.Rebase -> rebaseBranch(action.branch)
            is LogAction.RebaseInteractive -> rebaseInteractive(action.commit)
            is LogAction.RevertCommit -> revertCommit(action.commit)
            LogAction.UncommittedChangesSelected -> selectUncommittedChanges()
            LogAction.ShowStatusAmending -> selectUncommittedChanges(forceAmend = true)
            is LogAction.SearchValueChange -> onSearchValueChanged(action.filter)
            is LogAction.ToggleColumn -> updateLogColumns { it.toggled(action.column) }
            is LogAction.ResizeColumn -> updateLogColumns { it.resized(action.column, action.width) }
            is LogAction.SetDateShowsTime -> updateLogColumns { it.withDateShowingTime(action.showTime) }
            is LogAction.SetGraphMaxWidth -> updateLogColumns { it.withGraphMaxWidth(action.width) }
            LogAction.ResetColumns -> updateLogColumns { LogColumnsSettings() }
        }
    }

    private fun checkoutTag(tag: Tag) = checkoutCommitUseCase(tag.commitHash)
    private fun checkoutCommit(commit: Commit) = checkoutCommitUseCase(commit)
    private fun cherryPickCommit(commit: Commit) = cherryPickCommitUseCase(commit)
    private fun revertCommit(commit: Commit) = revertCommitUseCase(commit)

    fun selectUncommittedChanges(forceAmend: Boolean = false) = viewModelScope.launch {
        selectedItem.value = SelectedItem.UncommittedChanges

        val searchValue = logSearchFilterResults.value
        if (searchValue is LogSearch.SearchResults) {
            val lastIndexSelected = getLastIndexSelected()

            logSearchFilterResults.value = searchValue.copy(index = lastIndexSelected)
        }

        if (forceAmend) {
            statusViewModelExtender.amend(true)
        }
    }

    private fun getLastIndexSelected(): Int {
        val logSearchFilterResultsValue = logSearchFilterResults.value

        return if (logSearchFilterResultsValue is LogSearch.SearchResults) {
            logSearchFilterResultsValue.index
        } else
            NONE_MATCHING_INDEX
    }

    fun selectCommit(commit: Commit) = viewModelScope.launch {
        selectedItem.value = SelectedItem.CommitItem(commit, isStash = false)

        val searchValue = logSearchFilterResults.value
        if (searchValue is LogSearch.SearchResults) {
            var index = searchValue.commits.indexOfFirst { it.hash == commit.hash }

            if (index == -1)
                index = getLastIndexSelected()
            else
                index += 1  // +1 because UI count starts at 1

            logSearchFilterResults.value = searchValue.copy(index = index)
        }
    }

    fun onSearchValueChanged(searchTerm: String) = viewModelScope.launch {
        val logStatusValue = logState.value

        savedSearchFilter = searchTerm

        if (searchTerm.isNotBlank()) {
            val lowercaseValue = searchTerm.lowercase()
            val plotCommitList = logStatusValue.commitList

            val matchingCommits = plotCommitList.commits.filter {
                it.value.message.lowercase().contains(lowercaseValue) ||
                        it.value.author.name.orEmpty().lowercase().contains(lowercaseValue) ||
                        it.value.committer.name.orEmpty().lowercase().contains(lowercaseValue) ||
                        it.value.hash.lowercase().contains(lowercaseValue)
            }

            var startingUiIndex = NONE_MATCHING_INDEX

            if (matchingCommits.isNotEmpty()) {
                _focusCommit.emit(matchingCommits.entries.first().value)
                startingUiIndex = FIRST_INDEX
            }

            // TODO Instead of casting commits to list, use LinkedHashMap everywhere
            logSearchFilterResults.value = LogSearch.SearchResults(matchingCommits.values.toList(), startingUiIndex)
        } else {
            logSearchFilterResults.value = LogSearch.SearchResults(emptyList(), NONE_MATCHING_INDEX)
        }
    }

    suspend fun selectPreviousFilterCommit() {
        val logSearchFilterResultsValue = logSearchFilterResults.value

        if (logSearchFilterResultsValue !is LogSearch.SearchResults) {
            return
        }

        val index = logSearchFilterResultsValue.index
        val commits = logSearchFilterResultsValue.commits

        if (index == NONE_MATCHING_INDEX || index == FIRST_INDEX)
            return

        val newIndex = index - 1
        val newCommitToSelect = commits[newIndex - 1]

        logSearchFilterResults.value = logSearchFilterResultsValue.copy(index = newIndex)
        _focusCommit.emit(newCommitToSelect)
    }

    suspend fun selectNextFilterCommit() {
        val logSearchFilterResultsValue = logSearchFilterResults.value

        if (logSearchFilterResultsValue !is LogSearch.SearchResults) {
            return
        }

        val index = logSearchFilterResultsValue.index
        val commits = logSearchFilterResultsValue.commits
        val totalCount = logSearchFilterResultsValue.totalCount

        if (index == NONE_MATCHING_INDEX || index == totalCount)
            return

        val newIndex = index + 1
        // Use index instead of newIndex because Kotlin arrays start at 0 while the UI count starts at 1
        val newCommitToSelect = commits[index]

        logSearchFilterResults.value = logSearchFilterResultsValue.copy(index = newIndex)
        _focusCommit.emit(newCommitToSelect)
    }

    fun closeSearch() {
        logSearchFilterResults.value = LogSearch.NotSearching
    }

    fun addSearchToCloseableView() = tabScope.launch {
        addCloseableView(CloseableView.LOG_SEARCH)
    }

    private fun removeSearchFromCloseableView() = tabScope.launch {
        removeCloseableView(CloseableView.LOG_SEARCH)
    }

    private fun rebaseInteractive(commit: Commit) = startRebaseInteractiveUseCase(commit)

    fun loadMoreLogItems(firstVisibleItemIndex: Int) = viewModelScope.launch {
        val logState = this@RepositoryOpenViewModel.logState.value

        val numberOfCommitsDisplayed = logState
            .commitList
            .count()

        if (
            loadItemsMutex.isLocked ||
            lastIndexUsedToLoadData in firstVisibleItemIndex..numberOfCommitsDisplayed // TODO what happens if the number of commits has been somehow reduced?
        ) {
            return@launch
        }
        loadItemsMutex.withLock {
            lastIndexUsedToLoadData = firstVisibleItemIndex

            if (logState.isLoading)
                return@launch

            increaseLogCountUseCase(numberOfCommitsDisplayed + INCREMENTAL_COMMITS_LOAD)
        }
    }

    private fun setFilesChangedView(viewState: FilesViewState) = tabScope.launch {
        appSettings.setConfiguration(AppConfig.FilesChangedView(viewState))
    }

    private val refreshDiffFlow = repositoryStateRepository
        .completedTasks
        .map { tasks ->
            tasks.filter { task ->
                task is CompletedTask.Success && (
                        task.taskType is TaskType.StageFile ||
                                task.taskType is TaskType.DoCommit ||
                                task.taskType is TaskType.StageAllFiles ||
                                task.taskType is TaskType.StageHunk ||
                                task.taskType is TaskType.StageLine ||
                                task.taskType is TaskType.StageDir ||
                                task.taskType is TaskType.UnstageAllFiles ||
                                task.taskType is TaskType.UnstageFile ||
                                task.taskType is TaskType.UnstageHunk ||
                                task.taskType is TaskType.UnstageDir ||
                                task.taskType is TaskType.UnstageLine
                        )
            }
        }
        .distinctUntilChanged()

    val diffTypeFlow = settings.diffTextViewType
    val isDisplayFullFile = settings.diffDisplayFullFile

    val diffRefreshTrigger = repositoryStateRepository
        .refreshTriggered
        .filter { it.contains(DataToRefresh.ALL) || it.contains(DataToRefresh.STATUS) }
        .onStart { emit(emptyList()) }

    val diffResult: StateFlow<ViewDiffResult?> = combine(
        diffSelected,
        refreshDiffFlow,
        diffTypeFlow,
        isDisplayFullFile,
        diffRefreshTrigger,
    ) { diffSelected, _, diffType, isDisplayFullFile, _ ->
        if (diffSelected?.entries?.count() == 1) {
            val diff = loadDiff(diffSelected.entries.first(), diffType, isDisplayFullFile)

            if (diff is ViewDiffResult.Loaded) {
                addToCloseables()
            }

            diff
        } else {
            ViewDiffResult.DiffNotFound(null)
        }
    }.stateIn(initialValue = null as ViewDiffResult?)

    val isRepositoryInSafeState = repositoryState
        .map { it == RepositoryState.SAFE }

    private var diffJob: Job? = null

    val lazyListState = MutableStateFlow(
        LazyListState(
            0,
            0
        )
    )

    private suspend fun loadDiff(
        diffType: DiffType,
        diffTextType: DiffTextViewType,
        isDisplayFullFile: Boolean
    ): ViewDiffResult {
        return getDiffUseCase(diffType, diffTextType, isDisplayFullFile)
    }

    fun stageHunk(diffEntry: DiffEntry, hunk: Hunk) = stageHunkUseCase(diffEntry, hunk)

    fun resetHunk(diffEntry: DiffEntry, hunk: Hunk) = resetHunkUseCase(diffEntry, hunk)

    fun unstageHunk(diffEntry: DiffEntry, hunk: Hunk) = unstageHunkUseCase(diffEntry, hunk)

    fun stageFile(statusEntry: StatusEntry) = statusStageUseCase(statusEntry)

    fun unstageFile(statusEntry: StatusEntry) = statusUnstageUseCase(statusEntry)

    fun cancelRunningJobs() {
        diffJob?.cancel()
    }

    fun changeTextDiffType(newDiffType: DiffTextViewType) = tabScope.launch {
        settings.setConfiguration(AppConfig.DiffTextViewType(newDiffType))
    }

    fun changeDisplayFullFile(isDisplayFullFile: Boolean) = tabScope.launch {
        settings.setConfiguration(AppConfig.DiffDisplayFullFile(isDisplayFullFile))
    }

    fun stageHunkLine(entry: DiffEntry, hunk: Hunk, line: Line) = stageHunkLineUseCase(entry, hunk, line)

    fun unstageHunkLine(entry: DiffEntry, hunk: Hunk, line: Line) = unstageHunkLineUseCase(entry, hunk, line)

    fun openFileWithExternalApp(path: String) {
        openFileInExternalAppUseCase(path)
    }

    fun discardHunkLine(entry: DiffEntry, hunk: Hunk, line: Line) = discardHunkLineUseCase(entry, hunk, line)

    fun openSubmodule(path: String) {
        val repositoryPath = repositoryDataRepository.repositoryPath

        // TODO RepositoryPath point to .git dir instead of worktree? Fix if so
        if (repositoryPath != null) {
            viewModelScope.launch {
                appViewModel.addNewTabFromPath("$repositoryPath/$path", true)
            }
        }
    }

    fun addToCloseables() = tabScope.launch {
        addCloseableView(CloseableView.DIFF)
    }

    private fun removeFromCloseables() = tabScope.launch {
        removeCloseableView(CloseableView.DIFF)
    }

    fun reset() {
        cancelRunningJobs()
        removeFromCloseables()
    }

    fun clearDiff() {
        val diff = when (val state = diffResult.value) {
            is ViewDiffResult.DiffNotFound -> state.diffType
            is ViewDiffResult.Loaded -> state.diffType
            is ViewDiffResult.Loading -> state.diffType
            else -> null
        }

        if (diff != null) {
            when (diff) {
                is DiffType.CommitDiff -> removeSelectedDiff(setOf(diff))
                is DiffType.UncommittedDiff -> removeSelectedDiff(
                    setOf(diff),
                    diff.entryType,
                )
            }
        }
    }

    var rewordSteps = ArrayDeque<RebaseLine>()

    fun removeSelectedDiff(selectedToRemove: Set<DiffType.CommitDiff>) {
        val diffSelected = diffSelected.value

        if (diffSelected is DiffSelected.CommitedChanges) {
            val newDiffSelected = diffSelected.copy(items = diffSelected.items - selectedToRemove)
            this.diffSelected.value = newDiffSelected
        }
    }

    fun removeSelectedDiff(selectedToRemove: Set<DiffType.UncommittedDiff>, entryType: EntryType) {
        val diffSelected = diffSelected.value

        if (diffSelected is DiffSelected.UncommittedChanges && diffSelected.entryType == entryType) {
            val newDiffSelected = diffSelected.copy(items = diffSelected.items - selectedToRemove)
            this.diffSelected.value = newDiffSelected
        }
    }

    private fun isSameRebase(rebaseLines: List<RebaseLine>, state: RebaseInteractiveState): Boolean {
        if (state is RebaseInteractiveState.AwaitingInteraction) {
            val stepsList = state.data

            if (rebaseLines.count() != stepsList.count()) {
                return false
            }

            return rebaseLines.map { it.commit } == stepsList.map { it.commit }
        }

        return false
    }

    fun continueRebaseInteractive() {
        val rebaseState = this.rebaseInteractiveState.value

        val interactiveHandlerContinue = object : InteractiveHandler {
            override fun prepareSteps(steps: MutableList<RebaseTodoLine>) {
                if (rebaseState !is RebaseInteractiveState.AwaitingInteraction) {
                    throw Exception("prepareSteps called when rebaseState is not Loaded") // Should never happen, just in case
                }

                val newSteps = rebaseState.data.toMutableList()
                rewordSteps = ArrayDeque(newSteps.filter { it.action == RebaseLine.Action.REWORD })

                val newRebaseTodoLines = newSteps
                    .filter { it.action != RebaseLine.Action.DROP } // Remove dropped lines
                    .map {
                        RebaseTodoLine(
                            when (it.action) {
                                RebaseLine.Action.PICK -> RebaseTodoLine.Action.PICK
                                RebaseLine.Action.REWORD -> RebaseTodoLine.Action.REWORD
                                RebaseLine.Action.SQUASH -> RebaseTodoLine.Action.SQUASH
                                RebaseLine.Action.FIXUP -> RebaseTodoLine.Action.FIXUP
                                RebaseLine.Action.EDIT -> RebaseTodoLine.Action.EDIT
                                RebaseLine.Action.COMMENT -> RebaseTodoLine.Action.COMMENT
                                else -> throw IllegalStateException("Illegal action ${it.action}")
                            },
                            AbbreviatedObjectId.fromString(it.commit),
                            it.shortMessage,
                        )
                    }

                steps.clear()
                steps.addAll(newRebaseTodoLines)
            }

            override fun modifyCommitMessage(commit: String): String {
                // This can be called when there aren't any reword steps if squash is used.
                val step = rewordSteps.removeFirstOrNull() ?: return commit

                val rebaseState = rebaseInteractiveState.value
                if (rebaseState !is RebaseInteractiveState.AwaitingInteraction) {
                    throw Exception("modifyCommitMessage called when rebaseState is not Loaded") // Should never happen, just in case
                }

                return rebaseState
                    .data
                    .firstOrNull { it.commit == step.commit }
                    ?.let { line ->
                        line.modifiedMessage ?: line.fullMessage
                    }
                    ?: throw InvalidMessageException("Message for commit $commit is unexpectedly null")
            }
        }

        resumeRebaseInteractiveUseCase(interactiveHandlerContinue)
    }

    fun onCommitMessageChanged(rebaseLine: RebaseLine, newMessage: String) {
        val rebaseState = this.rebaseInteractiveState.value

        if (rebaseState !is RebaseInteractiveState.AwaitingInteraction)
            return


        val rebaseLines = rebaseState.data.toMutableList()
        val currentValueIndex = rebaseLines.indexOf(rebaseLine)

        rebaseLines[currentValueIndex] = rebaseLine.copy(modifiedMessage = newMessage)

        this.rebaseInteractiveState.value = rebaseState.copy(data = rebaseLines)
    }

    fun onCommitActionChanged(commit: String, rebaseAction: RebaseLine.Action) {
        val rebaseState = rebaseInteractiveState.value

        if (rebaseState !is RebaseInteractiveState.AwaitingInteraction)
            return

        val newStepsList =
            rebaseState.data.toMutableList() // Change the list reference to update the flow with .toList()

        val stepIndex = newStepsList.indexOfFirst {
            it.commit == commit
        }

        if (stepIndex >= 0) {
            val step = newStepsList[stepIndex]
            newStepsList[stepIndex] = step.copy(action = rebaseAction)

            this.rebaseInteractiveState.value = rebaseState.copy(data = newStepsList)
        }
    }

    fun cancel() {
        abortRebaseUseCase()
    }

    fun selectLine(line: RebaseLine) = viewModelScope.launch {
        val fullCommit = getCommitFromRebaseLineUseCase(line.commit, line.fullMessage).okOrNull()

        if (fullCommit != null) {
            selectedItem.value = SelectedItem.CommitItem(fullCommit, isStash = false)
        }
    }

    fun moveCommit(from: Int, to: Int) {
        val state = rebaseInteractiveState.value

        if (state is RebaseInteractiveState.AwaitingInteraction) {

            val newStepsList = state.data.toMutableList().apply {
                add(to, removeAt(from))
            }

            this.rebaseInteractiveState.value = state.copy(data = newStepsList)
        }
    }


    fun openFileInFolder(folderPath: String?) = viewModelScope.launch {
        if (folderPath != null) {
            val worktreeDir = getWorktreeUseCase().okOrNull() ?: return@launch
            val file = File(worktreeDir + File.separator + folderPath)
            file.openFileInFolder()
        }
    }
}


sealed interface BlameState {
    data class Loading(val filePath: String) : BlameState

    data class Loaded(val filePath: String, val blameResult: BlameResult, val isMinimized: Boolean = false) : BlameState

    data object None : BlameState
}


fun <T> Flow<T>.mutableStateIn(scope: CoroutineScope, initialValue: T): MutableStateFlow<T> {
    val state = MutableStateFlow(initialValue)
    scope.launch {
        this@mutableStateIn.collect {
            state.value = it
        }
    }

    return state
}