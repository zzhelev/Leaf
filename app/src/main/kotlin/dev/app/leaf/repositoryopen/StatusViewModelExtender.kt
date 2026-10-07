package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.collectLatestInCoroutineScope
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.extensions.nullIf
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.usecases.*
import dev.app.leaf.extensions.stateIn
import dev.app.leaf.ui.status.*
import dev.app.leaf.ui.toUiDataState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

private const val PERSIST_MESSAGE_DELAY_IN_MS = 1_000L


class StatusViewModelExtender @AssistedInject constructor(
    private val unstageUseCase: StatusUnstageUseCase,
    private val stageUseCase: StatusStageUseCase,
    private val stageAllUseCase: StatusStageAllUseCase,
    private val unstageAllUseCase: StatusUnstageAllUseCase,
    private val discardEntriesUseCase: DiscardEntriesUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val resetRepositoryStateUseCase: ResetRepositoryStateUseCase,
    private val abortRebaseUseCase: AbortRebaseUseCase,
    private val continueRebaseUseCase: ContinueRebaseUseCase,
    private val skipRebaseUseCase: SkipRebaseUseCase,
    private val doCommitUseCase: DoCommitUseCase,
    private val getAuthorUseCase: GetAuthorUseCase,
    private val saveAuthorUseCase: SaveAuthorUseCase,
    private val appSettings: AppSettingsService,
    private val addSelectedDiffUseCase: AddSelectedDiffUseCase,
    private val stageByDirectoryUseCase: StageByDirectoryUseCase,
    private val unstageByDirectoryUseCase: UnstageByDirectoryUseCase,
    private val persistCommitMessageUseCase: PersistCommitMessageUseCase,
    private val repositoryDataRepository: RepositoryDataRepository,
    @Assisted private val viewModelScope: CoroutineScope,
    @Assisted private val filesViewState: StateFlow<FilesViewState>,
    @Assisted private val diffSelected: StateFlow<DiffSelected?>,
    @Assisted private val rebaseInteractiveState: StateFlow<RebaseInteractiveState>,
    @Assisted private val onOpenFileInFolder: (String) -> Unit,
    @Assisted private val onDiffSelected: (DiffSelected) -> Unit,
    @Assisted private val onRemoveEntriesFromSelection: (Set<DiffType.UncommittedDiff>, EntryType) -> Unit,
    @Assisted private val onViewStateChanged: (FilesViewState) -> Unit,
    @Assisted("addCloseableView") private val addCloseableView: (CloseableView) -> Unit,
    @Assisted("removeCloseableView") private val removeCloseableView: (CloseableView) -> Unit,
) : CoroutineScope by viewModelScope {

    @AssistedFactory
    interface Factory {
        fun create(
            viewModelScope: CoroutineScope,
            filesViewState: StateFlow<FilesViewState>,
            diffSelected: StateFlow<DiffSelected?>,
            rebaseInteractiveState: StateFlow<RebaseInteractiveState>,
            onOpenFileInFolder: (String) -> Unit,
            onDiffSelected: (DiffSelected) -> Unit,
            onRemoveEntriesFromSelection: (Set<DiffType.UncommittedDiff>, EntryType) -> Unit,
            onViewStateChanged: (FilesViewState) -> Unit,
            @Assisted("addCloseableView") addCloseableView: (CloseableView) -> Unit,
            @Assisted("removeCloseableView") removeCloseableView: (CloseableView) -> Unit,
        ): StatusViewModelExtender
    }

    val showSearchUnstaged: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val showSearchStaged: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val searchFilterUnstaged: StateFlow<TextFieldValue>
        field = MutableStateFlow(TextFieldValue(""))

    val searchFilterStaged: StateFlow<TextFieldValue>
        field = MutableStateFlow(TextFieldValue(""))

    /** Folders closed in each pane's folder tree. Kept while the tab is open. */
    private val stagedCollapsedFolders = MutableStateFlow(CollapsedFolders())
    private val unstagedCollapsedFolders = MutableStateFlow(CollapsedFolders())

    val swapUncommittedChanges = appSettings.swapStatusPanes

    private val normalCommitMessage = persistedCommitMessageFlow { it.commitMessage }

    private val mergeCommitMessage = persistedCommitMessageFlow { it.mergeMessage }

    private val commitMessage = combine(
        normalCommitMessage,
        mergeCommitMessage,
        repositoryDataRepository.repositoryState,
    ) { normalCommitMessage, mergeCommitMessage, repositoryState ->
        val state = repositoryState.dataOrNull()

        if (state?.isMerging == true) {
            mergeCommitMessage
        } else {
            normalCommitMessage
        }
    }.stateIn(TextFieldValue(""))


    private fun persistedCommitMessageFlow(filter: (PersistedCommitMessage) -> String?): MutableStateFlow<TextFieldValue> {
        val textFlow = MutableStateFlow(TextFieldValue(""))

        viewModelScope.launch {
            repositoryDataRepository
                .persistedCommitMessage
                .map {
                    it
                        .dataOrNull()
                        ?.let { persistedCommitMessage -> filter(persistedCommitMessage) }
                }
                .distinctUntilChanged()
                .collect {
                    if (textFlow.value.text.isBlank()) {
                        val newText = it.orEmpty()
                        textFlow.value = TextFieldValue(newText, selection = TextRange(newText.count()))
                    }
                }
        }

        return textFlow
    }


    // When false, disable "amend previous commit"
    // TODO This should be improved in case it's a dangling branch, shouldn't happen often but could be a thing
    val previousCommitMessage = combine(
        repositoryDataRepository.currentBranch.toUiDataState(),
        repositoryDataRepository.log.toUiDataState(),
    ) { branchState, log ->
        val branch = branchState.data ?: return@combine null

        val commits = log.data ?: GraphCommits()
        commits[branch.hash]?.message
    }

    val committerDataRequestState: StateFlow<CommitterDataRequestState>
        field = MutableStateFlow<CommitterDataRequestState>(CommitterDataRequestState.None)

    val isAmend: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val isAmendRebaseInteractive: StateFlow<Boolean>
        field = rebaseInteractiveState
            .map {
                it is RebaseInteractiveState.ProcessingCommits
            }
            .mutableStateIn(viewModelScope, false)


    private var persistMessageJob: Job? = null

    val selectedStagedDiffEntries = diffSelected
        .map { diffSelected ->
            getDiffSelectedEntriesByEntryType(diffSelected, EntryType.STAGED)
        }
        .stateIn(emptyList())

    val selectedUnstagedDiffEntries = diffSelected
        .map { diffSelected ->
            getDiffSelectedEntriesByEntryType(diffSelected, EntryType.UNSTAGED)
        }
        .stateIn(emptyList())


    private val repositoryPath = repositoryDataRepository
        .repositorySelectionState
        .map {
            if (it is RepositorySelectionState.Open) {
                it.path
            } else {
                null
            }
        }

    val statusState = combineStatusState(
        repositoryDataRepository.status.toUiDataState(),
        showSearchStaged,
        searchFilterStaged,
        showSearchUnstaged,
        searchFilterUnstaged,
        filesViewState,
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
        repositoryDataRepository.repositoryState.toUiDataState(),
        repositoryPath,
    )
        .stateIn(StatusState())

    init {
        showSearchStaged.collectLatestInCoroutineScope {
            if (it) {
                addStagedSearchToCloseableView()
            } else {
                removeStagedSearchToCloseableView()
            }
        }

        showSearchUnstaged.collectLatestInCoroutineScope {
            if (it) {
                addUnstagedSearchToCloseableView()
            } else {
                removeUnstagedSearchToCloseableView()
            }
        }

        isSearchingFlow(EntryType.STAGED).collectLatestInCoroutineScope {
            stagedCollapsedFolders.update(CollapsedFolders::withoutSearch)
        }

        isSearchingFlow(EntryType.UNSTAGED).collectLatestInCoroutineScope {
            unstagedCollapsedFolders.update(CollapsedFolders::withoutSearch)
        }


        diffSelected
            .combine(statusState) { diffSelected, state ->
                diffSelected to state
            }
            .collectLatestInCoroutineScope { (diffSelected, state) ->
                if (diffSelected is DiffSelected.UncommittedChanges) {
                    val entries = state.getEntriesByEntryType(diffSelected.entryType)

                    val diffSelectedToRemove = diffSelected.items
                        .asSequence()
                        .filter { diff ->
                            entries.none { statusEntry ->
                                statusEntry.filePath == diff.statusEntry.filePath &&
                                        statusEntry.entryType == diff.statusEntry.entryType
                            }
                        }
                        .toSet()

                    if (diffSelectedToRemove.isNotEmpty()) {
                        onRemoveEntriesFromSelection(diffSelectedToRemove, diffSelected.entryType)
                    }
                }
            }

    }

    fun onAction(action: StatusAction) = when (action) {
        is StatusAction.EntryAction -> {
            when (action.statusEntry.entryType) {
                EntryType.STAGED -> unstage(action.statusEntry)
                EntryType.UNSTAGED -> stage(action.statusEntry)
            }
        }

        is StatusAction.Reset -> when (action.statusEntry.entryType) {
            EntryType.STAGED -> discardStaged(listOf(action.statusEntry))
            EntryType.UNSTAGED -> discardUnstaged(listOf(action.statusEntry))
        }

        is StatusAction.AllEntriesAction -> when (action.entryType) {
            EntryType.STAGED -> unstageAll()
            EntryType.UNSTAGED -> stageAll()
        }

        is StatusAction.Delete -> deleteFile(action.statusEntry)
        is StatusAction.DirectoryAction -> when (action.entryType) {
            EntryType.STAGED -> unstageByDirectory(action.path)
            EntryType.UNSTAGED -> stageByDirectory(action.path)
        }

        is StatusAction.FolderRowAction -> folderRowAction(action.path, action.entryType)

        is StatusAction.OpenInFolder -> onOpenFileInFolder(action.path)
        is StatusAction.SearchFilterChanged -> when (action.entryType) {
            EntryType.STAGED -> onSearchFilterChangedStaged(action.filter)
            EntryType.UNSTAGED -> onSearchFilterChangedUnstaged(action.filter)
        }

        is StatusAction.SelectEntry -> selectEntries(
            action.isCtrlPressed,
            action.isMetaPressed,
            action.isShiftPressed,
            diffEntries = action.diffEntries,
            selectedEntries = action.selectedEntries,
            entry = action.statusEntry
        )

        is StatusAction.ViewStateChanged -> onViewStateChanged(action.viewState)
        is StatusAction.TreeDirectoryToggle -> toggleTreeDirectoryVisibility(action.path, action.entryType)
        is StatusAction.DiscardSelected -> when (action.entryType) {
            EntryType.STAGED -> discardSelectedStaged()
            EntryType.UNSTAGED -> discardSelectedUnstaged()
        }

        is StatusAction.SelectedEntriesAction -> when (action.entryType) {
            EntryType.STAGED -> unstageAll()
            EntryType.UNSTAGED -> stageAll()
        }

        StatusAction.AbortRebase -> abortRebase()
        is StatusAction.AcceptCommitterData -> acceptCommitterData(action.authorInfo, action.persist)
        StatusAction.RejectCommitterData -> rejectCommitterData()
        StatusAction.AddStagedSearchToCloseableView -> addStagedSearchToCloseableView()
        StatusAction.AddUnstagedSearchToCloseableView -> addUnstagedSearchToCloseableView()
        is StatusAction.Commit -> commit(action.message)
        is StatusAction.ContinueRebase -> continueRebase(action.message)
        StatusAction.ResetRepositoryState -> resetRepoState()
        is StatusAction.SearchFilterToggledStaged -> searchFilterToggledStaged()
        is StatusAction.SearchFilterToggledUnstaged -> searchFilterToggledUnstaged()
        StatusAction.SkipRebase -> skipRebase()
        is StatusAction.ToggleAmend -> amend(action.toggle)
        is StatusAction.ToggleAmendRebaseInteractive -> amendRebaseInteractive(action.toggle)
        is StatusAction.UpdateCommitMessage -> updateCommitMessage(action.message)
    }

    fun rejectCommitterData() {
        this.committerDataRequestState.value = CommitterDataRequestState.Reject
    }

    fun acceptCommitterData(newAuthorInfo: AuthorInfo, persist: Boolean) {
        this.committerDataRequestState.value = CommitterDataRequestState.Accepted(newAuthorInfo, persist)
    }

    fun updateCommitMessage(message: TextFieldValue) {
        val repositoryState = repositoryDataRepository.repositoryState.value.dataOrNull()

        if (repositoryState?.isMerging == true) {
            mergeCommitMessage.value = message
        } else {
            normalCommitMessage.value = message
        }

        persistMessage(message.text)
    }

    private fun persistMessage(newMessage: String?) {
        persistMessageJob?.cancel()

        persistMessageJob = viewModelScope.launch {
            delay(PERSIST_MESSAGE_DELAY_IN_MS.milliseconds)
            persistCommitMessageUseCase(newMessage)
        }
    }

    private fun getDiffSelectedEntriesByEntryType(
        diffSelected: DiffSelected?,
        entryType: EntryType
    ): List<DiffType.UncommittedDiff> {
        val diffUncommited = diffSelected as? DiffSelected.UncommittedChanges

        return if (diffUncommited?.entryType == entryType) {
            diffUncommited.items
        } else {
            emptySet()
        }.toList()
    }


    fun searchFilterToggledStaged(visible: Boolean? = null) {
        showSearchStaged.value = visible ?: !showSearchStaged.value
    }

    fun onSearchFilterChangedStaged(filter: TextFieldValue) {
        searchFilterStaged.value = filter
    }

    fun searchFilterToggledUnstaged(visible: Boolean? = null) {
        showSearchUnstaged.value = visible ?: !showSearchUnstaged.value
    }

    fun onSearchFilterChangedUnstaged(filter: TextFieldValue) {
        searchFilterUnstaged.value = filter
    }

    private fun discardSelectedStaged() {
        discardStaged(selectedStagedDiffEntries.value.map { it.statusEntry })
    }

    private fun discardSelectedUnstaged() {
        discardUnstaged(selectedUnstagedDiffEntries.value.map { it.statusEntry })
    }

    private fun stageByDirectory(dir: String) = stageByDirectoryUseCase(dir)

    private fun folderRowAction(path: String, entryType: EntryType) {
        if (!isSearching(entryType)) {
            when (entryType) {
                EntryType.STAGED -> unstageByDirectory(path)
                EntryType.UNSTAGED -> stageByDirectory(path)
            }

            return
        }

        // During a search the folder shows only its matching files, so only those are staged or unstaged
        val entries = statusState.value.searchMatchesUnder(path, entryType)

        // An empty list would mean every file
        if (entries.isEmpty()) return

        when (entryType) {
            EntryType.STAGED -> unstageAllUseCase(entries)
            EntryType.UNSTAGED -> stageAllUseCase(entries)
        }
    }

    private fun unstageByDirectory(dir: String) = unstageByDirectoryUseCase(dir)

    fun selectEntries(
        isCtrlPressed: Boolean,
        isMetaPressed: Boolean,
        isShiftPressed: Boolean,
        diffEntries: List<StatusEntry>,
        selectedEntries: List<DiffType.UncommittedDiff>,
        entry: StatusEntry,
    ) {
        val selectionType = getEntriesToSelect(
            isCtrlPressed = isCtrlPressed,
            isMetaPressed = isMetaPressed,
            isShiftPressed = isShiftPressed,
            diffEntries = diffEntries,
            selectedEntries = selectedEntries,
            entry = entry,
        )

        when (selectionType) {
            is SelectionType.AddMultipleEntries -> this.selectEntries(
                entry.entryType,
                selectionType.entries,
                addToExisting = true
            )

            is SelectionType.AppendSingleEntry -> this.selectEntries(
                entry.entryType,
                listOf(selectionType.entry),
                addToExisting = true,
            )

            is SelectionType.RemoveSingleEntry -> this.onRemoveEntriesFromSelection(
                setOf(DiffType.UncommittedDiff(selectionType.entry, entry.entryType)),
                entry.entryType,
            )

            is SelectionType.SetSingleEntry -> this.selectEntries(
                entry.entryType,
                listOf(selectionType.entry),
                addToExisting = false,
            )
        }
    }

    private fun getEntriesToSelect(
        isCtrlPressed: Boolean,
        isMetaPressed: Boolean,
        isShiftPressed: Boolean,
        diffEntries: List<StatusEntry>,
        selectedEntries: List<DiffType.UncommittedDiff>,
        entry: StatusEntry,
    ): SelectionType<StatusEntry> {
        return when {
            isShiftPressed -> {
                val entries =
                    getEntriesInBetween(
                        diffEntries,
                        selectedEntries,
                        entry,
                    )

                SelectionType.AddMultipleEntries(entries)
            }

            currentOs == OS.MAC && isMetaPressed || isCtrlPressed -> {
                val isAlreadyPresent = selectedEntries.any { it.statusEntry == entry }

                if (isAlreadyPresent) {
                    SelectionType.RemoveSingleEntry(entry)
                } else {
                    SelectionType.AppendSingleEntry(entry)
                }
            }

            else -> SelectionType.SetSingleEntry(entry)
        }
    }

    private fun getEntriesInBetween(
        diffEntries: List<StatusEntry>,
        selectedEntries: List<DiffType>,
        entry: StatusEntry,
    ): List<StatusEntry> {
        val entries = diffEntries

        val last = selectedEntries.lastOrNull()
        // Should always be uncommitted diff at this point
        val lastItemIndex = (last as? DiffType.UncommittedDiff)?.let { entries.indexOf(it.statusEntry) } ?: -1

        // The last selected file can be hidden by the search or inside a closed folder
        return if (lastItemIndex == -1) {
            listOf(entry)
        } else {
            val selectedItemIndex = entries.indexOf(entry)

            val entriesToSelect =
                entries.subList(min(lastItemIndex, selectedItemIndex), max(lastItemIndex, selectedItemIndex) + 1)

            entriesToSelect
        }
    }

    private fun selectEntries(entryType: EntryType, entries: List<StatusEntry>, addToExisting: Boolean) {
        val newValue = addSelectedDiffUseCase(
            diffSelected.value,
            entries.map {
                DiffType.UncommittedDiff(
                    statusEntry = it,
                    entryType = entryType,
                )
            },
            addToExisting,
            entryType,
        )

        onDiffSelected(newValue)
    }


    private fun stageAll() {
        val entries = selectedUnstagedDiffEntries
            .value
            .ifEmpty { null }
            ?.map { it.statusEntry }
            ?.nullIf { it.count() == 1 }

        stageAllUseCase(entries)
    }

    private fun discardStaged(statusEntries: List<StatusEntry>) {
        discardEntriesUseCase(statusEntries, isStaged = true)
    }

    private fun discardUnstaged(statusEntries: List<StatusEntry>) {
        discardEntriesUseCase(statusEntries, isStaged = false)
    }

    private fun stage(statusEntry: StatusEntry) = stageUseCase(statusEntry)
    private fun unstage(statusEntry: StatusEntry) = unstageUseCase(statusEntry)

    private fun unstageAll() {
        val entries = selectedStagedDiffEntries
            .value
            .ifEmpty { null }
            ?.map { it.statusEntry }
            ?.nullIf { it.count() == 1 }

        unstageAllUseCase(entries)
    }

    fun addStagedSearchToCloseableView() {
        addSearchToCloseView(CloseableView.STAGED_CHANGES_SEARCH)
    }

    private fun removeStagedSearchToCloseableView() {
        removeSearchFromCloseView(CloseableView.STAGED_CHANGES_SEARCH)
    }

    fun addUnstagedSearchToCloseableView() {
        addSearchToCloseView(CloseableView.UNSTAGED_CHANGES_SEARCH)
    }

    private fun removeUnstagedSearchToCloseableView() {
        removeSearchFromCloseView(CloseableView.UNSTAGED_CHANGES_SEARCH)
    }

    private fun removeSearchFromCloseView(view: CloseableView) = viewModelScope.launch {
        removeCloseableView(view)
    }

    fun toggleTreeDirectoryVisibility(directoryPath: String, entryType: EntryType) {
        val isSearching = isSearching(entryType)
        val folders = when (entryType) {
            EntryType.STAGED -> stagedCollapsedFolders
            EntryType.UNSTAGED -> unstagedCollapsedFolders
        }

        folders.update { it.toggled(directoryPath, isSearching) }
    }

    private fun isSearching(entryType: EntryType): Boolean = when (entryType) {
        EntryType.STAGED -> showSearchStaged.value && searchFilterStaged.value.text.isNotBlank()
        EntryType.UNSTAGED -> showSearchUnstaged.value && searchFilterUnstaged.value.text.isNotBlank()
    }

    private fun isSearchingFlow(entryType: EntryType): Flow<Boolean> {
        val (showSearch, searchFilter) = when (entryType) {
            EntryType.STAGED -> showSearchStaged to searchFilterStaged
            EntryType.UNSTAGED -> showSearchUnstaged to searchFilterUnstaged
        }

        return combine(showSearch, searchFilter) { show, filter -> show && filter.text.isNotBlank() }
            .distinctUntilChanged()
    }


    private fun addSearchToCloseView(view: CloseableView) = viewModelScope.launch {
        addCloseableView(view)
    }

    private fun continueRebase(message: String) = viewModelScope.launch {
        continueRebaseUseCase(
            message = message,
            isAmendRebaseInteractive = isAmendRebaseInteractive.value,
            repositoryState = repositoryDataRepository.repositoryState.value.dataOrNull() ?: RepositoryState.SAFE,
            rebaseInteractiveState = rebaseInteractiveState.value,
            onIdentityRequest = { getIdentity() }
        )
    }

    private fun abortRebase() = abortRebaseUseCase()
    private fun skipRebase() = skipRebaseUseCase()
    private fun resetRepoState() = resetRepositoryStateUseCase()

    private fun deleteFile(statusEntry: StatusEntry) = deleteFileUseCase(statusEntry.filePath)

    fun amend(isAmend: Boolean) {
        this.isAmend.value = isAmend

        if (this.commitMessage.value.text.isEmpty()) {
            val previousCommitMessage = this.statusState.value.previousCommitMessage.orEmpty()

            updateCommitMessage(TextFieldValue(previousCommitMessage, selection = TextRange(previousCommitMessage.count())))
        }
    }

    fun amendRebaseInteractive(isAmend: Boolean) {
        isAmendRebaseInteractive.value = isAmend
    }

    fun commit(message: String) = viewModelScope.launch {
        val amend = isAmend.value

        val personIdent = getIdentity()

        // If someone clicks on commit before persisting the message (as it has a delay), cancel it.
        val hadOngoingPersistJob = persistMessageJob?.isActive == true
        persistMessageJob?.cancelAndJoin()

        val doCommitResult = doCommitUseCase(message, amend, personIdent).await()

        if (doCommitResult is Either.Ok) {
            updateCommitMessage(TextFieldValue(""))
            isAmend.value = false
        } else {
            // If committing failed and the message was going to be persisted before the commit, restart the
            // persistence task
            if (hadOngoingPersistJob) {
                persistMessage(message)
            }
        }
    }

    private suspend fun getIdentity(): Identity? {
        val author = when (val data = getAuthorUseCase()) {
            is Either.Err -> AuthorInfo(Identity(null, null), Identity(null, null))
            is Either.Ok -> data.value
        }

        return if (
            author.repositoryIdentity.name.isNullOrEmpty() && author.globalIdentity.name.isNullOrEmpty() ||
            author.repositoryIdentity.email.isNullOrEmpty() && author.globalIdentity.email.isNullOrEmpty()
        ) {
            committerDataRequestState.value = CommitterDataRequestState.WaitingInput(author)

            var committerData = committerDataRequestState.value

            while (committerData is CommitterDataRequestState.WaitingInput) {
                committerData = committerDataRequestState.value
            }

            if (committerData is CommitterDataRequestState.Accepted) {
                val authorInfo = committerData.authorInfo

                if (committerData.persist) {
                    saveAuthorUseCase(authorInfo)
                }

                Identity(authorInfo.globalIdentity.name.orEmpty(), authorInfo.globalIdentity.email.orEmpty())
            } else {
                throw CancellationException("Author info request cancelled")
            }
        } else
            null
    }

}