package dev.app.leaf.repositoryopen

import androidx.compose.foundation.ScrollState
import dev.app.leaf.TabViewModel
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.merge_automatic_stash_description
import dev.app.leaf.app.generated.resources.pull_with_merge_automatic_stash_description
import dev.app.leaf.app.generated.resources.pull_with_merge_from_specific_branch_automatic_stash_description
import dev.app.leaf.collectLatestInViewModel
import dev.app.leaf.common.flows.combine
import dev.app.leaf.common.flows.invert
import dev.app.leaf.domain.diff.rowKeptAcross
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.AppStateManager
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.conflicts.ConflictsToOpen
import dev.app.leaf.domain.conflicts.conflictsToOpen
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.UnsupportedIndexError
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.exceptions.InvalidMessageException
import dev.app.leaf.domain.extensions.lowercaseContains
import dev.app.leaf.domain.extensions.openFileInFolder
import dev.app.leaf.domain.extensions.toMutableSetAndAdd
import dev.app.leaf.domain.extensions.toMutableSetAndRemove
import dev.app.leaf.domain.log.LogMove
import dev.app.leaf.domain.log.SearchStep
import dev.app.leaf.domain.log.movedLogSelection
import dev.app.leaf.domain.log.steppedSearchIndex
import dev.app.leaf.domain.index.UnsupportedIndex
import dev.app.leaf.domain.index.conversionSteps
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.refresh.WatchRegistrations
import dev.app.leaf.domain.refresh.WatchUsage
import dev.app.leaf.domain.refresh.WorktreesVisibility
import dev.app.leaf.domain.repositories.*
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RefFolderExpansion
import dev.app.leaf.domain.sorting.RefPanelSettings
import dev.app.leaf.domain.sorting.RefSection
import dev.app.leaf.domain.sorting.RefSortState
import dev.app.leaf.domain.usecases.*
import dev.app.leaf.domain.worktrees.unlockWorktreeSteps
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.worktrees.withIdle
import dev.app.leaf.domain.worktrees.withOrigins
import dev.app.leaf.domain.worktrees.worktreePickerRows
import dev.app.leaf.extensions.stateIn
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.system.OpenUrlInBrowserUseCase
import dev.app.leaf.system.PickerType
import dev.app.leaf.terminal.OpenRepositoryInTerminalGitAction
import dev.app.leaf.ui.AppViewModel
import dev.app.leaf.ui.BranchWorktreesState
import dev.app.leaf.ui.IVerticalSplitPaneConfig
import dev.app.leaf.ui.VerticalSplitPaneConfig
import dev.app.leaf.ui.WorktreePickerState
import dev.app.leaf.ui.dialogs.FastForwardOffer
import dev.app.leaf.ui.status.StatusAction
import dev.app.leaf.ui.UiDataState
import dev.app.leaf.ui.toUiDataState
import dev.app.leaf.updates.Update
import dev.app.leaf.updates.UpdatesRepository
import dev.app.leaf.viewmodels.ConflictResolverViewModel
import dev.app.leaf.viewmodels.HistoryViewModel
import dev.app.leaf.viewmodels.RepositoryTabViewModel
import dev.app.leaf.viewmodels.newLazyListState
import dev.app.leaf.viewmodels.sidepanel.*
import kotlinx.coroutines.CoroutineScope
import dev.app.leaf.domain.rebase.withFirstStepPicked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val conflictResolverViewModelProvider: Provider<ConflictResolverViewModel>,
    private val getMergeToolUseCase: GetMergeToolUseCase,
    val appStateManager: AppStateManager,
    private val openFilePickerUseCase: OpenFilePickerUseCase,
    private val openUrlInBrowserUseCase: OpenUrlInBrowserUseCase,
    private val appViewModel: AppViewModel,
    private val tabScope: TabCoroutineScope,
    private val verticalSplitPaneConfig: VerticalSplitPaneConfig,
    private val refreshDataUseCase: RefreshDataUseCase,
    private val increaseLogCountUseCase: IncreaseLogCountUseCase,
    private val loadLogUntilCommitUseCase: LoadLogUntilCommitUseCase,
    private val blameFileUseCase: BlameFileUseCase,
    updatesRepository: UpdatesRepository,
    watchRegistrations: WatchRegistrations,
    private val fetchRemotesUseCase: FetchRemotesUseCase,
    private val deleteRemoteInfoUseCase: DeleteRemoteInfoUseCase,
    private val checkoutCommitUseCase: CheckoutCommitUseCase,
    private val rebaseBranchUseCase: RebaseBranchUseCase,
    private val deleteRemoteBranchUseCase: DeleteRemoteBranchUseCase,
    private val deleteSubmoduleUseCase: DeleteSubmoduleUseCase,
    private val mergeBranchUseCase: MergeBranchUseCase,
    private val checkoutBranchUseCase: CheckoutBranchUseCase,
    private val switchWorktreeUseCase: SwitchWorktreeUseCase,
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
    private val pollWorktreesUseCase: PollWorktreesUseCase,
    private val setWorktreeBaseBranchUseCase: SetWorktreeBaseBranchUseCase,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
    private val followRemovedWorktreeUseCase: FollowRemovedWorktreeUseCase,
    private val comparisonViewModelExtenderFactory: ComparisonViewModelExtender.Factory,
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

    val isExpandedWorktrees: StateFlow<Boolean>
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

    /**
     * The worktrees, each with who made it and whether it's idle (fork-only): the user's label rules and idle days apply
     * as soon as they change.
     */
    private val worktrees: StateFlow<UiDataState<WorktreeList>> = combine(
        repositoryDataRepository.worktrees.toUiDataState(),
        settings.worktreeLabels,
        settings.worktreesUntouchedDays,
    ) { worktrees, rules, untouchedDays ->
        val list = worktrees.data?.withOrigins(rules)?.withIdle(untouchedDays, System.currentTimeMillis())

        UiDataState(worktrees.isLoading, list, worktrees.error)
    }
        // Real paths read the file system
        .flowOn(Dispatchers.IO)
        .stateIn(UiDataState(isLoading = true, data = null, error = null))

    val worktreesState: StateFlow<WorktreesState> =
        combineWorktreesState(worktrees, isExpandedWorktrees, filter, branches).stateIn(WorktreesState())

    /** Whether the side panel shows the worktree list, which refreshes on an interval meanwhile. */
    private val worktreesVisibility = MutableStateFlow(WorktreesVisibility.Hidden)

    init {
        viewModelScope.launch { pollWorktreesUseCase(worktreesVisibility) }
        viewModelScope.launch { followRemovedWorktreeUseCase() }

        // Fork-only: the staged result of a merge into the base, its message and any conflicts, and the conflicts of a
        // rebase onto the base wait in the uncommitted changes, which a selected commit would hide
        viewModelScope.launch {
            worktreeResultsToShow(repositoryStateRepository.completedTasks).collect { selectUncommittedChanges() }
        }
    }

    /** The worktrees that use each local branch, for the branch list and the log. */
    val branchWorktrees: StateFlow<BranchWorktreesState> = worktrees
        .map { worktrees ->
            val list = worktrees.data
            BranchWorktreesState(list?.usersByBranch().orEmpty(), list?.baseBranch, list?.baseChoice())
        }
        .distinctUntilChanged()
        .stateIn(BranchWorktreesState())

    /** The worktrees for the toolbar's picker, or null when the repository has only one (fork-only). */
    val worktreePickerState: StateFlow<WorktreePickerState?> = worktrees
        .map { worktrees ->
            val list = worktrees.data ?: return@map null

            worktreePickerRows(list, System.currentTimeMillis())?.let { WorktreePickerState(it, list.baseBranch) }
        }
        .stateIn(null)

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

    private val verticalListState = MutableStateFlow(newLazyListState())
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

    // Fork-only: the parents in the commit details, and the jump to one of them (upstream issue 270)
    private val commitParents = CommitParentsController(
        scope = viewModelScope,
        selectedCommit = selectedItem.map { (it as? SelectedItem.CommitBasedItem)?.commit },
        loadedCommits = logState
            .map { it.commitList }
            .distinctUntilChanged { old, new -> old === new }
            .stateIn(logState.value.commitList),
        stashes = logState.map { it.stashes },
        loadLogUntilCommit = { hash, onProgress -> loadLogUntilCommitUseCase(hash, onProgress = onProgress) },
        select = { commit -> selectCommit(commit, isStash = false, scrollToItem = true) },
    )

    val commitParentsState = commitParents.state

    fun onParentClicked(hash: String) = commitParents.onParentClicked(hash)

    fun toggleMoreParents() = commitParents.toggleExpanded()

    // Fork-only: a comparison of two commits or branches, and the review of a worktree's changes, in place of Files
    // changed (docs/fork/compare.md)
    private val comparisonViewModelExtender = comparisonViewModelExtenderFactory.create(
        viewModelScope,
        filesViewState,
        selectedItem,
        onSelectItem = { selectedItem.value = it },
        onDiffSelected = { diffSelected.value = it },
        onViewStateChanged = ::setFilesChangedView,
        addCloseableView = ::addCloseableView,
        removeCloseableView = ::removeCloseableView,
        selectionFor = { hash ->
            val log = logState.value

            log.commitList[hash]?.commit
                ?.let { SelectedItem.CommitItem(it, isStash = hash in log.stashes, scrollToItem = false) }
                ?: SelectedItem.UncommittedChanges
        },
    )

    val comparisonState = comparisonViewModelExtender.state

    val statusState = statusViewModelExtender.statusState

    /** What ours and theirs are, while files have conflicts (fork-only). */
    val conflictSides = statusViewModelExtender.conflictSides

    /**
     * The worktree's index, while JGit can't read it (fork-only): status fails then. Kept while the status reloads, so
     * the strip doesn't flicker, and gone once a status loads, as after the index was converted.
     */
    val unsupportedIndex: StateFlow<UnsupportedIndex?> = repositoryDataRepository.status
        .filter { it !is DataState.Loading }
        .map { ((it as? DataState.Error)?.error as? UnsupportedIndexError)?.index }
        .distinctUntilChanged()
        .stateIn(null)

    /** Converts the worktree's index with the commands the strip's dialog showed (fork-only). */
    fun convertIndex(index: UnsupportedIndex) {
        runWorktreeStepsUseCase(TaskType.ConvertIndex, index.conversionSteps())
    }

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

                CloseableView.COMPARISON -> comparisonViewModelExtender.end()
                CloseableView.COMPARISON_FULL_VIEW -> leaveComparisonFullView()

                CloseableView.COMMIT_FIELD_SUGGESTIONS -> statusViewModelExtender.dismissCoAuthorSuggestions()

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

        // Fork-only: a comparison that ends takes its file's diff with it, which would stay over the log
        comparisonState.map { it != null }.distinctUntilChanged().drop(1).filter { !it }.collectLatestInViewModel {
            clearComparisonDiff()
        }

        // Fork-only: closes what showed the files of the worktree that the tab switched away from
        repositoryDataRepository.repositorySelectionState.worktreeSwitches().collectLatestInViewModel {
            if (diffSelected.value is DiffSelected.UncommittedChanges) {
                diffSelected.value = null
            }

            resetBlameState()
            closeHistory()
            comparisonViewModelExtender.end()
            // Its picks aren't saved: they were made in the other worktree's file
            conflictResolverViewModel?.closeNow()
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

    fun onExpandWorktrees() {
        isExpandedWorktrees.invert()
    }

    fun onWorktreesVisibilityChanged(visibility: WorktreesVisibility) {
        worktreesVisibility.value = visibility
    }

    /**
     * Compares the worktrees to [branch] (`refs/heads/x`, `refs/remotes/origin/x`), or to the automatic base when
     * it's null.
     */
    fun chooseWorktreesBase(branch: String?) {
        tabScope.launch {
            val result = setWorktreeBaseBranchUseCase(branch)

            if (result is Either.Err) {
                printError(TAG, "Failed to choose $branch as the worktrees' base branch: ${result.error}")
            }
        }
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

    /** Selects the commit that [worktree] has checked out, as its branch's when it's on one. */
    fun selectWorktree(worktree: Worktree) {
        val branch = worktree.branch?.let { name -> branches.value.data?.firstOrNull { it.name == name } }
        val headSha = worktree.headSha

        when {
            branch != null -> selectBranch(branch)
            headSha != null -> viewModelScope.launch {
                val commit = getCommitFromHashUseCase(headSha).okOrNull()

                if (commit != null) {
                    // The log scrolls to it, as it does to a branch's commit
                    selectedItem.value = SelectedItem.CommitItem(commit, isStash = false, scrollToItem = true)
                }
            }
        }
    }

    /** Goes back from a comparison's full view to the log, without the diff that the full view showed (fork-only). */
    private fun leaveComparisonFullView() {
        comparisonViewModelExtender.leaveFullView()
        clearComparisonDiff()
    }

    /** Closes a comparison's diff, and its place among the views that Esc closes, which an extra Esc would close. */
    private fun clearComparisonDiff() {
        if (diffSelected.value is DiffSelected.CommitedChanges) {
            diffSelected.value = null
            removeCloseableView(CloseableView.DIFF)
        }
    }

    /** Shows what [worktree]'s branch would bring into the base, in place of Files changed (fork-only). */
    fun reviewWorktree(worktree: Worktree) {
        comparisonViewModelExtender.openReview(worktree, worktreesState.value.baseBranch)
    }

    fun onAction(action: ComparisonAction) {
        if (action == ComparisonAction.LeaveFullView) {
            leaveComparisonFullView()
            return
        }

        val review = comparisonState.value?.review

        // Another worktree's uncommitted changes show once the tab has switched to it
        if (action == ComparisonAction.ShowUncommittedChanges && review != null && !review.isTabWorktree) {
            if (!review.canSwitchTo) return

            switchWorktreeUseCase(review.path)
        }

        comparisonViewModelExtender.onAction(action)
    }

    /**
     * Switches the tab to [worktree] (fork-only). Nothing happens when the tab can't switch to it
     * ([Worktree.canSwitchTo]): the tab already shows it, or it has no folder to open.
     */
    fun switchToWorktree(worktree: Worktree) {
        if (worktree.canSwitchTo) {
            whenConflictResolverLeft { switchWorktreeUseCase(worktree.path) }
        }
    }

    /** Selects the tab that shows [worktree], or opens it in a new tab (fork-only), when the tab can switch to it. */
    fun openWorktreeInNewTab(worktree: Worktree) {
        if (worktree.canSwitchTo) {
            appViewModel.selectOrOpenTab(worktree.path)
        }
    }

    /** Unlocks [worktree] (fork-only). It loses nothing, so it doesn't ask first. */
    fun unlockWorktree(worktree: Worktree) = viewModelScope.launch {
        val mainWorktree = repositoryDataRepository.worktrees
            .first { it !is DataState.Loading }
            .dataOrNull()
            ?.mainWorktree
            ?: return@launch

        runWorktreeStepsUseCase(
            TaskType.UnlockWorktree,
            unlockWorktreeSteps(worktree, mainWorktree),
            arrayOf(DataToRefresh.WORKTREES),
        )
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

    /**
     * Checks out [branch], or switches to the worktree that has it (fork-only), as a branch can be checked out in one
     * worktree at a time. When that worktree's folder is missing, the checkout's refusal says where it is.
     */
    fun checkoutBranch(branch: Branch) {
        val worktree = branchWorktrees.value.usersOf(branch)?.worktreeToSwitchTo

        if (worktree != null) {
            switchToWorktree(worktree)
        } else {
            checkoutBranchUseCase(branch)
        }
    }

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
        selectCommit(stash, isStash = true, scrollToItem = true)
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
        // Fork-only: this override was left empty, so the view model's pipelines outlived its tab
        historyViewModel?.onClear()
        conflictResolverViewModel?.onClear()
        super.onClear()
    }

    /**
     * To make sure the tab opens the new repository with a clean state,
     * instead of opening the repo in the same ViewModel we simply create a new tab with a new TabViewModel
     * replacing the current tab
     */
    fun openAnotherRepository(directory: String, tab: RepositoryTabViewModel) {
        // Fork-only: the conflict resolver asks first when it has changes that aren't saved
        whenConflictResolverLeft { appViewModel.replaceTab(tab, directory) }
    }


    private fun refreshRepositoryInfo() {
        refreshDataUseCase(DataToRefresh.ALL)
    }

    fun openDirectoryPicker(): String? {
        val latestDirectoryOpened = appStateManager.latestOpenedRepositoryPath

        return openFilePickerUseCase(PickerType.DIRECTORIES, latestDirectoryOpened)
    }

    val update: StateFlow<Update?> = updatesRepository.update

    /** How many folders every tab's watcher holds, while that is near what macOS delivers events for (fork-only). */
    val watchWarning: StateFlow<WatchUsage?> = watchRegistrations.warning

    fun blameFile(filePath: String) {
        viewModelScope.launch {
            _blameState.value = BlameState.Loading(filePath)

            when (val result = blameFileUseCase(filePath)) {
                is Either.Err -> {
                    resetBlameState()
                    // Fork-only: a blame that fails says why, as a file that a partial clone couldn't download
                    repositoryStateRepository.addCompletedTaskFailed(
                        TaskType.BlameFile,
                        result.error,
                        FailureSeverity.HIGH,
                    )
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
        historyViewModel?.onClear()
        historyViewModel = historyViewModelProvider.get()
        historyViewModel?.fileHistory(filePath)
        _showHistory.value = true
    }

    fun closeHistory() {
        _showHistory.value = false
        historyViewModel?.onClear()
        historyViewModel = null
    }

    /** Fork-only: the built-in conflict resolver, which takes the whole tab, as file history does. */
    val showConflictResolver: StateFlow<Boolean>
        field = MutableStateFlow(false)

    var conflictResolverViewModel: ConflictResolverViewModel? = null
        private set

    /** Opens [path], or the first conflicted file, in the resolver, or switches the open resolver to it. */
    fun openConflictResolver(path: String?) {
        val open = conflictResolverViewModel

        if (open != null) {
            path?.let { open.selectFile(it) }
            return
        }

        closeHistory()

        conflictResolverViewModel = conflictResolverViewModelProvider.get().also { viewModel ->
            viewModel.open(path, onClose = ::removeConflictResolver)
        }
        showConflictResolver.value = true
    }

    /** Closes the resolver, which asks first when the file has changes that aren't saved (Esc, "← Changes"). */
    fun closeConflictResolver() {
        conflictResolverViewModel?.close()
    }

    /**
     * Runs [then], which leaves the resolver's file: switching the tab to another worktree, opening another repository
     * in it, or closing it. When the resolver has changes that aren't saved, it asks first, in its own strip: Save,
     * Discard or Cancel.
     */
    fun whenConflictResolverLeft(then: () -> Unit) {
        val resolver = conflictResolverViewModel

        if (resolver == null) then() else resolver.leaveThen(then)
    }

    /** Whether the resolver has changes that aren't saved, which closing the tab would lose. */
    val conflictResolverHasUnsavedChanges: Boolean
        get() = conflictResolverViewModel?.hasUnsavedChanges == true

    /** The resolver's file when it has changes that aren't saved, which quitting Leaf would lose (fork-only). */
    val conflictResolverUnsavedFile: String?
        get() = conflictResolverViewModel?.unsavedFile

    /** Saves the resolver's file before Leaf quits: see [ConflictResolverViewModel.saveBeforeQuit]. */
    fun saveConflictResolverBeforeQuit(onSaved: () -> Unit, onFailed: () -> Unit) {
        val resolver = conflictResolverViewModel

        if (resolver == null) onSaved() else resolver.saveBeforeQuit(onSaved, onFailed)
    }

    /** Esc in the resolver: finishes editing first, then leaves Focus, then closes it. */
    fun escapeConflictResolver() {
        conflictResolverViewModel?.escape()
    }

    /** The resolver's Save, for Cmd+S, which is Stash elsewhere: git can't stash while files have conflicts. */
    fun saveInConflictResolver() {
        conflictResolverViewModel?.save()
    }

    private fun removeConflictResolver() {
        showConflictResolver.value = false
        conflictResolverViewModel?.onClear()
        conflictResolverViewModel = null
    }

    /**
     * Double-click on a conflicted file and the banner's Resolve conflicts: the built-in resolver, or the external merge
     * tool when the settings pick it, it can take the file and git has one set (Settings → Conflicts).
     */
    private fun openConflict(entry: StatusEntry) {
        viewModelScope.launch {
            val external = appSettings.conflictOpener.first() == ConflictOpener.EXTERNAL_TOOL &&
                entry.canOpenInMergeTool &&
                getMergeToolUseCase() != null

            if (external) {
                statusViewModelExtender.onAction(StatusAction.OpenInExternalMergeTool(entry))
            } else {
                openConflictResolver(entry.filePath)
            }
        }
    }

    /**
     * The banner's Resolve conflicts: the built-in resolver on the first conflicted file, or with the external merge tool
     * picked, every file it can take, one after another (`conflictsToOpen`).
     */
    fun resolveConflicts() {
        val entries = statusState.value.unstaged

        viewModelScope.launch {
            val inMergeTool = appSettings.conflictOpener.first() == ConflictOpener.EXTERNAL_TOOL &&
                getMergeToolUseCase() != null

            when (val toOpen = conflictsToOpen(entries, inMergeTool)) {
                is ConflictsToOpen.InMergeTool ->
                    statusViewModelExtender.onAction(StatusAction.OpenInExternalMergeToolInTurn(toOpen.filePaths))

                is ConflictsToOpen.InResolver -> openConflictResolver(toOpen.filePath)
            }
        }
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
        when {
            // Fork-only: a conflicted file opens to be resolved, instead of being staged with its markers
            action is StatusAction.EntryAction && action.statusEntry.statusType == StatusType.CONFLICTING &&
                action.statusEntry.entryType == EntryType.UNSTAGED -> openConflict(action.statusEntry)

            action is StatusAction.OpenInResolver -> openConflictResolver(action.statusEntry.filePath)
            else -> statusViewModelExtender.onAction(action)
        }
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
            is LogAction.CommitSelected -> selectCommit(action.commit, action.isStash)
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
        applyUncommittedChangesSelection()

        if (forceAmend) {
            statusViewModelExtender.amend(true)
        }
    }

    private fun applyUncommittedChangesSelection() {
        selectedItem.value = SelectedItem.UncommittedChanges

        val searchValue = logSearchFilterResults.value
        if (searchValue is LogSearch.SearchResults) {
            val lastIndexSelected = getLastIndexSelected()

            logSearchFilterResults.value = searchValue.copy(index = lastIndexSelected)
        }
    }

    private fun getLastIndexSelected(): Int {
        val logSearchFilterResultsValue = logSearchFilterResults.value

        return if (logSearchFilterResultsValue is LogSearch.SearchResults) {
            logSearchFilterResultsValue.index
        } else
            NONE_MATCHING_INDEX
    }

    fun selectCommit(commit: Commit, isStash: Boolean, scrollToItem: Boolean = false) = viewModelScope.launch {
        applyCommitSelection(commit, isStash, scrollToItem)
    }

    private fun applyCommitSelection(commit: Commit, isStash: Boolean, scrollToItem: Boolean) {
        selectedItem.value = SelectedItem.CommitItem(commit, isStash = isStash, scrollToItem = scrollToItem)

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

    /**
     * Moves the log's selection with the keyboard (fork-only) and returns the row to bring into view, or null when
     * nothing moved. Rows are counted as in the list: the uncommitted changes' row first when the log shows one.
     *
     * It reads the selection and writes the new one right here, not in a launched coroutine: key repeat comes faster
     * than a recomposition, so a step computed from what the view last drew would repeat or skip rows, and launched
     * writes could land out of order. A step selects the row and scrolls to it, as a click does. The commit pane loads
     * only the last selection (`flatMapLatest`), so holding a key doesn't queue a load per row.
     */
    fun moveLogSelection(move: LogMove, pageSize: Int, hasUncommittedRow: Boolean): Int? {
        val log = logState.value
        val commits = log.commitList.values.toList()
        val offset = if (hasUncommittedRow) 1 else 0

        val target = movedLogSelection(
            selected = selectedItem.value,
            commitHashes = commits.map { it.hash },
            hasUncommittedRow = hasUncommittedRow,
            move = move,
            pageSize = pageSize,
        ) ?: return null

        if (target < offset) {
            applyUncommittedChangesSelection()
        } else {
            val commit = commits[target - offset]
            applyCommitSelection(commit.commit, isStash = log.stashes.contains(commit.hash), scrollToItem = false)
        }

        return target
    }

    /** The search for the last term typed. Each new term cancels it, so that a slower earlier search can't win. */
    private var logSearchJob: Job? = null

    fun onSearchValueChanged(searchTerm: String): Job {
        logSearchJob?.cancel()

        return viewModelScope.launch { searchLog(searchTerm) }.also { logSearchJob = it }
    }

    private suspend fun searchLog(searchTerm: String) {
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
            // A newer term may have come while the commits were filtered
            currentCoroutineContext().ensureActive()

            if (matchingCommits.isNotEmpty()) {
                _focusCommit.emit(matchingCommits.entries.first().value)
                startingUiIndex = FIRST_INDEX
            }

            // TODO Instead of casting commits to list, use LinkedHashMap everywhere
            logSearchFilterResults.value = LogSearch.SearchResults(matchingCommits.values.toList(), startingUiIndex)
        } else {
            logSearchFilterResults.value = LogSearch.SearchResults(emptyList(), NONE_MATCHING_INDEX, hasTerm = false)
        }
    }

    suspend fun selectPreviousFilterCommit() = stepSearchResult(SearchStep.PREVIOUS)

    suspend fun selectNextFilterCommit() = stepSearchResult(SearchStep.NEXT)

    /**
     * Moves to the previous or next search result, wrapping at the ends (fork-only), and scrolls to it,
     * also when it's the one already there, as with a single result. `update` keeps a step from putting an older
     * list back over the results of a search typed meanwhile.
     */
    private suspend fun stepSearchResult(step: SearchStep) {
        var commitToFocus: GraphCommit? = null

        logSearchFilterResults.update { current ->
            // The block runs again when another write came in between
            commitToFocus = null

            if (current !is LogSearch.SearchResults) return@update current

            val newIndex = steppedSearchIndex(current.index, current.commits.size, step)

            commitToFocus = current.commits.getOrNull(newIndex - 1) // The index starts at 1, the list at 0

            if (newIndex == current.index) current else current.copy(index = newIndex)
        }

        commitToFocus?.let { _focusCommit.emit(it) }
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

    /** "Try again" in a diff that couldn't be loaded (fork-only). */
    private val diffRetries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val diffRefreshTrigger = merge(
        repositoryStateRepository
            .refreshTriggered
            .filter { it.contains(DataToRefresh.ALL) || it.contains(DataToRefresh.STATUS) },
        diffRetries.map { emptyList() },
    )
        .onStart { emit(emptyList()) }

    /** Loads the diff again, after it couldn't be loaded (fork-only). */
    fun retryDiff() {
        diffRetries.tryEmit(Unit)
    }

    // Fork-only: the setting is read again whenever any setting is written, which would load the diff again
    val diffWhitespace: StateFlow<DiffWhitespaceMode> = settings.diffWhitespace.stateIn(DiffWhitespaceMode.DEFAULT)

    val diffResult: StateFlow<ViewDiffResult?> = combine(
        diffSelected,
        refreshDiffFlow,
        diffTypeFlow.distinctUntilChanged(),
        isDisplayFullFile.distinctUntilChanged(),
        diffRefreshTrigger,
        settings.diffWhitespace.distinctUntilChanged(),
    ) { diffSelected, _, diffType, isDisplayFullFile, _, whitespace ->
        if (diffSelected?.entries?.count() == 1) {
            val diff = loadDiff(diffSelected.entries.first(), diffType, isDisplayFullFile, whitespace)

            // Fork-only: Esc closes a diff that couldn't be loaded too
            if (diff is ViewDiffResult.Failed) {
                addToCloseables()
            }

            if (diff is ViewDiffResult.Loaded) {
                addToCloseables()

                // Another diff starts at its top, while a refresh of the same one, such as after staging a hunk,
                // keeps its scroll
                if (diff.diffType != lazyListStateDiffType) {
                    lazyListStateDiffType = diff.diffType
                    lazyListState.value = newLazyListState()
                } else if (whitespace != loadedWithWhitespace) {
                    // Fork-only: ignoring whitespace gives the list other rows, so the line at its top is kept, not its row
                    rowKeptAcross(lastLoadedDiff?.diffResult, lazyListState.value.firstVisibleItemIndex, diff.diffResult)
                        ?.let { row -> lazyListState.value = newLazyListState(row) }
                }

                lastLoadedDiff = diff
                loadedWithWhitespace = whitespace
            }

            diff
        } else {
            ViewDiffResult.DiffNotFound(null)
        }
    }.stateIn(initialValue = null as ViewDiffResult?)

    val isRepositoryInSafeState = repositoryState
        .map { it == RepositoryState.SAFE }

    private var diffJob: Job? = null

    /** The diff that is shown, and the whitespace mode that it was made with. */
    private var lastLoadedDiff: ViewDiffResult.Loaded? = null
    private var loadedWithWhitespace: DiffWhitespaceMode? = null

    // Fork-only: made through newLazyListState, as diffResult replaces it off the UI thread
    val lazyListState = MutableStateFlow(newLazyListState())

    /** The diff that [lazyListState] scrolls. */
    private var lazyListStateDiffType: DiffType? = null

    private suspend fun loadDiff(
        diffType: DiffType,
        diffTextType: DiffTextViewType,
        isDisplayFullFile: Boolean,
        whitespace: DiffWhitespaceMode,
    ): ViewDiffResult {
        return getDiffUseCase(diffType, diffTextType, isDisplayFullFile, whitespace)
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

    /** Whether the diff pane wraps long lines (fork-only). True until the setting is read, as before it existed. */
    val diffWrapLines: StateFlow<Boolean> = settings.diffWrapLines.stateIn(true)

    fun changeDiffWrapLines(wrap: Boolean) = tabScope.launch {
        settings.setConfiguration(AppConfig.DiffWrapLines(wrap))
    }

    /** Whether the diffs mark spaces, tabs and line endings (fork-only). */
    val diffShowWhitespace: StateFlow<Boolean> = settings.diffShowWhitespace.stateIn(false)

    fun changeDiffWhitespace(mode: DiffWhitespaceMode) = tabScope.launch {
        settings.setConfiguration(AppConfig.DiffWhitespace(mode))
    }

    fun changeDiffShowWhitespace(show: Boolean) = tabScope.launch {
        settings.setConfiguration(AppConfig.DiffShowWhitespace(show))
    }

    fun stageHunkLine(entry: DiffEntry, hunk: Hunk, line: Line) = stageHunkLineUseCase(entry, hunk, line)

    fun unstageHunkLine(entry: DiffEntry, hunk: Hunk, line: Line) = unstageHunkLineUseCase(entry, hunk, line)

    fun openFileWithExternalApp(path: String) {
        openFileInExternalAppUseCase(path)
    }

    fun discardHunkLine(entry: DiffEntry, hunk: Hunk, line: Line) = discardHunkLineUseCase(entry, hunk, line)

    /** Whether discarding a hunk or a line asks first. True until the setting is read, so nothing goes unasked. */
    val confirmHunkAndLineDiscards: StateFlow<Boolean> = settings.confirmHunkAndLineDiscards.stateIn(true)

    /** "Don't ask again" in the confirmation of a hunk or a line. Settings can turn it back on. */
    fun stopConfirmingHunkAndLineDiscards() = tabScope.launch {
        settings.setConfiguration(AppConfig.ConfirmHunkAndLineDiscards(false))
    }

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
            is ViewDiffResult.Failed -> state.diffType
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

                // In case a dropped step left a squash or fixup first
                val newSteps = rebaseState.data.withFirstStepPicked().toMutableList()
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
            selectedItem.value = SelectedItem.CommitItem(fullCommit, isStash = false, scrollToItem = false)
        }
    }

    fun moveCommit(from: Int, to: Int) {
        val state = rebaseInteractiveState.value

        if (state is RebaseInteractiveState.AwaitingInteraction) {

            val newStepsList = state.data.toMutableList().apply {
                add(to, removeAt(from))
            }

            // A squash or fixup moved to the top, or left there, becomes a pick
            this.rebaseInteractiveState.value = state.copy(data = newStepsList.withFirstStepPicked())
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