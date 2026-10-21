package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.collectLatestInCoroutineScope
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.commitmessage.CoAuthor
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.commitmessage.ConventionalHistory
import dev.app.leaf.domain.commitmessage.conventionalHistory
import dev.app.leaf.domain.commitmessage.ticketKeyIn
import dev.app.leaf.domain.commitmessage.ticketKeyStyle
import dev.app.leaf.domain.commitmessage.MARKED_CO_AUTHOR_TRAILER
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.commitmessage.SignOffChanges
import dev.app.leaf.domain.SignOffConstants
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.commitmessage.appendTrailer
import dev.app.leaf.domain.commitmessage.coAuthorCandidates
import dev.app.leaf.domain.commitmessage.withEmptyCoAuthorLine
import dev.app.leaf.domain.commitmessage.withoutEmptyCoAuthorLines
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.selectionDiscard
import dev.app.leaf.domain.usecases.*
import dev.app.leaf.domain.worktrees.isSquashMergeInProgress
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
    private val discardAllUseCase: DiscardAllUseCase,
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
    private val statusSectionsConfig: StatusSectionsConfig,
    private val getConflictSidesUseCase: GetConflictSidesUseCase,
    private val openMergeToolUseCase: OpenMergeToolUseCase,
    private val resolveConflictUseCase: ResolveConflictUseCase,
    private val persistSquashMessageUseCase: PersistSquashMessageUseCase,
    private val abortSquashMergeUseCase: AbortSquashMergeUseCase,
    private val getCommitMessageCleanupUseCase: GetCommitMessageCleanupUseCase,
    private val addTrailerUseCase: AddTrailerUseCase,
    private val loadSignOffConfigUseCase: LoadSignOffConfigUseCase,
    private val saveSignOffConfigUseCase: SaveSignOffConfigUseCase,
    private val signOffChanges: SignOffChanges,
    private val getCommitlintRulesUseCase: GetCommitlintRulesUseCase,
    private val getCommitTemplateUseCase: GetCommitTemplateUseCase,
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

    private val squashCommitMessage = persistedCommitMessageFlow { it.squashMessage }

    /** Fork-only: each edit stop of a rebase has its own message, so the field starts over at the next one. */
    private val rebaseCommitMessage = persistedCommitMessageFlow(key = { it.rebaseEditStop }) { it.rebaseEditMessage }

    /**
     * Fork-only: whether a squash merge waits to be committed, which git tells only by its SQUASH_MSG and the index.
     * While any of them reloads, the last answer stays, so that the commit field and the banner don't flicker. It's
     * followed even while the status pane isn't shown, as typing and committing read it.
     */
    private val squashMergeInProgress = combine(
        repositoryDataRepository.repositoryState.filter { it !is DataState.Loading },
        repositoryDataRepository.persistedCommitMessage.filter { it !is DataState.Loading },
        repositoryDataRepository.status.filter { it !is DataState.Loading },
    ) { repositoryState, persistedCommitMessage, status ->
        isSquashMergeInProgress(
            repositoryState.dataOrNull(),
            persistedCommitMessage.dataOrNull()?.squashMessage,
            status.dataOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Fork-only: which message the commit field shows, and where its edits are saved. */
    private val commitMessageSource = combine(
        repositoryDataRepository.repositoryState,
        squashMergeInProgress,
    ) { repositoryState, squashMergeInProgress ->
        commitMessageSource(repositoryState.dataOrNull(), squashMergeInProgress)
    }.stateIn(CommitMessageSource.NORMAL)

    private val commitMessage = combine(
        normalCommitMessage,
        mergeCommitMessage,
        squashCommitMessage,
        rebaseCommitMessage,
        commitMessageSource,
    ) { normalCommitMessage, mergeCommitMessage, squashCommitMessage, rebaseCommitMessage, source ->
        when (source) {
            CommitMessageSource.NORMAL -> normalCommitMessage
            CommitMessageSource.MERGE -> mergeCommitMessage
            CommitMessageSource.SQUASH -> squashCommitMessage
            CommitMessageSource.REBASE -> rebaseCommitMessage
        }
    }.stateIn(TextFieldValue(""))

    private fun messageFlowOf(source: CommitMessageSource): MutableStateFlow<TextFieldValue> = when (source) {
        CommitMessageSource.NORMAL -> normalCommitMessage
        CommitMessageSource.MERGE -> mergeCommitMessage
        CommitMessageSource.SQUASH -> squashCommitMessage
        CommitMessageSource.REBASE -> rebaseCommitMessage
    }


    /**
     * Fork-only: a message whose [key] changes replaces the field's text, where another message only fills a blank
     * field.
     */
    private fun persistedCommitMessageFlow(
        key: (PersistedCommitMessage) -> String? = { null },
        filter: (PersistedCommitMessage) -> String?,
    ): MutableStateFlow<TextFieldValue> {
        val textFlow = MutableStateFlow(TextFieldValue(""))

        viewModelScope.launch {
            // Fork-only: starts over with the message of each worktree that the tab switches to
            flow {
                emit(null)
                emitAll(repositoryDataRepository.repositorySelectionState.worktreeSwitches())
            }.collectLatest { switchedTo ->
                if (switchedTo != null) {
                    textFlow.value = TextFieldValue("")
                }

                var shownKey: String? = null

                repositoryDataRepository
                    .persistedCommitMessage
                    .map {
                        it
                            .dataOrNull()
                            ?.let { persistedCommitMessage ->
                                key(persistedCommitMessage) to filter(persistedCommitMessage)
                            }
                    }
                    .distinctUntilChanged()
                    .collect { keyedMessage ->
                        val isOtherKey = keyedMessage != null && keyedMessage.first != shownKey

                        if (keyedMessage != null) {
                            shownKey = keyedMessage.first
                        }

                        if (isOtherKey || textFlow.value.text.isBlank()) {
                            val newText = keyedMessage?.second.orEmpty()
                            textFlow.value = TextFieldValue(newText, selection = TextRange(newText.count()))
                        }
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

    /**
     * Fork-only: what ours and theirs are, read again with each status that has conflicts, as the next commit of a
     * rebase can stop at the same files. While the status reloads, the last sides stay.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val conflictSides: StateFlow<ConflictSides?> = repositoryDataRepository.status
        .filter { it !is DataState.Loading }
        .mapLatest { status ->
            val hasConflicts = status.dataOrNull()?.unstaged.orEmpty().any { it.statusType == StatusType.CONFLICTING }

            if (hasConflicts) getConflictSidesUseCase() else null
        }
        .stateIn(null)

    /**
     * Fork-only: how git cleans up the commit message, read again with each status, as the config may have changed. It
     * reads JGit's cached config, which only reads its files again when they changed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val messageCleanup: StateFlow<MessageCleanup> = repositoryDataRepository.status
        .filter { it !is DataState.Loading }
        .mapLatest { getCommitMessageCleanupUseCase() }
        .stateIn(MessageCleanup.DEFAULT)

    /**
     * Fork-only: whom the commit field suggests as co-authors, from the history that the log has loaded, without the
     * user. Worked out again off the UI thread when the log or the identity changes.
     */
    private val coAuthorCandidates: Flow<List<CoAuthor>> = combine(
        repositoryDataRepository.log.map { it.dataOrNull() },
        repositoryDataRepository.author.map { it.dataOrNull()?.identityToUse() },
    ) { log, you ->
        coAuthorCandidates(log?.values?.map { it.commit }.orEmpty(), you)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .onStart { emit(emptyList()) }

    /**
     * Fork-only: the Conventional Commits types and scopes of the history that the log has loaded, for the commit field's
     * suggestions. Worked out again off the UI thread when the log changes.
     */
    private val conventionalHistory: Flow<ConventionalHistory> = repositoryDataRepository.log
        .map { log -> conventionalHistory(log.dataOrNull()?.values?.map { it.commit }.orEmpty()) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .onStart { emit(ConventionalHistory()) }

    /**
     * Fork-only: the worktree's commitlint config, which narrows the Conventional Commits suggestions, read again with
     * each status, as the file watcher refreshes it when the config changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val commitlintRules: StateFlow<CommitlintRules?> = repositoryDataRepository.status
        .filter { it !is DataState.Loading }
        .mapLatest { getCommitlintRulesUseCase() }
        .stateIn(null)

    /**
     * Fork-only: what an empty commit field offers to start from. The ticket key in the current branch's name, written
     * as the newest subjects write theirs (worked out off the UI thread when the log changes), and git's commit
     * template, read again with each status, as the config or the file may have changed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val messageStartInputs: Flow<MessageStartInputs> = combine(
        repositoryDataRepository.currentBranch
            .map { branch -> branch.dataOrNull()?.name?.let(::ticketKeyIn) }
            .distinctUntilChanged(),
        repositoryDataRepository.log
            .map { log -> ticketKeyStyle(log.dataOrNull()?.values?.map { it.commit }.orEmpty()) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default),
        repositoryDataRepository.status
            .filter { it !is DataState.Loading }
            .mapLatest { getCommitTemplateUseCase() }
            .distinctUntilChanged(),
    ) { key, keyStyle, template ->
        MessageStartInputs(key, keyStyle, template)
    }.onStart { emit(MessageStartInputs()) }

    /** Fork-only: the line that "Add co-author" added, while leaving it untouched takes it out again. */
    private val coAuthorInsertion = MutableStateFlow<CoAuthorInsertion?>(null)
    private val coAuthorDismissals = MutableStateFlow(0)
    private var coAuthorInsertions = 0L

    private val coAuthorState = combine(coAuthorCandidates, coAuthorInsertion, coAuthorDismissals) {
            candidates, insertion, dismissals ->
        CoAuthorState(candidates, insertion, dismissals)
    }

    /**
     * Fork-only: the repository's sign-off setting, per worktree in `<git dir>/leaf`. Read again with each status, as a
     * worktree switch changes it, and when the commit message's menu or the Actions list's dialog saves it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val isSignOff: StateFlow<Boolean> = combine(
        repositoryDataRepository.status.filter { it !is DataState.Loading },
        signOffChanges.count,
    ) { _, _ -> }
        .mapLatest { loadSignOffConfigUseCase().okOrNull()?.isEnabled == true }
        .stateIn(false)

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
        statusSectionsConfig.sizes,
        conflictSides,
        squashMergeInProgress,
        messageCleanup,
        appSettings.commitMessageHints,
        coAuthorState,
        isSignOff,
        conventionalHistory,
        commitlintRules,
        messageStartInputs,
    )
        .stateIn(StatusState())

    init {
        // Fork-only: a field fills from its file only while it's blank, so the message of a merge, cherry-pick, revert
        // or squash merge would stay after its file is gone, and show at the next one in place of git's. A squash merge
        // that stops at conflicts writes MERGE_MSG too. So the field is cleared once its file is gone, unless the field
        // still shows MERGE_MSG, which the user may have just emptied.
        combine(
            repositoryDataRepository.persistedCommitMessage.mapNotNull { (it as? DataState.Loaded)?.data },
            repositoryDataRepository.repositoryState.filter { it !is DataState.Loading },
        ) { messages, repositoryState ->
            messages to repositoryState.dataOrNull()
        }.collectLatestInCoroutineScope { (messages, repositoryState) ->
            val showsMergeMessage = commitMessageSource(repositoryState, false) == CommitMessageSource.MERGE

            if (messages.mergeMessage == null && !showsMergeMessage) {
                mergeCommitMessage.value = TextFieldValue("")
            }

            if (messages.squashMessage == null) {
                squashCommitMessage.value = TextFieldValue("")
            }
        }

        // Fork-only: what belonged to the worktree that the tab switched away from. A message still waiting to be saved
        // is saved there, and a commit or the next message doesn't cancel it.
        repositoryDataRepository.repositorySelectionState.worktreeSwitches().collectLatestInCoroutineScope {
            persistMessageJob = null
            isAmend.value = false
            stagedCollapsedFolders.value = CollapsedFolders()
            unstagedCollapsedFolders.value = CollapsedFolders()
        }

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
            // Only when the files change, not at each letter of the commit message
            .combine(statusState.distinctUntilChangedBy { it.staged to it.unstaged }) { diffSelected, state ->
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

        is StatusAction.DiscardAll -> discardAllUseCase(action.plan, action.deleteNewFiles)

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
        StatusAction.AbortSquashMerge -> abortSquashMergeUseCase()
        is StatusAction.SearchFilterToggledStaged -> searchFilterToggledStaged()
        is StatusAction.SearchFilterToggledUnstaged -> searchFilterToggledUnstaged()
        StatusAction.SkipRebase -> skipRebase()
        is StatusAction.ToggleAmend -> amend(action.toggle)
        is StatusAction.ToggleAmendRebaseInteractive -> amendRebaseInteractive(action.toggle)
        is StatusAction.UpdateCommitMessage -> updateCommitMessage(action.message)
        is StatusAction.SectionSizesChanged -> statusSectionsConfig.save(action.sizes)
        is StatusAction.OpenInExternalMergeTool -> openMergeToolUseCase(action.statusEntry.filePath)
        is StatusAction.OpenInExternalMergeToolInTurn -> openMergeToolUseCase.inTurn(action.filePaths)
        is StatusAction.UseConflictSide -> resolveConflictUseCase(action.filePaths, action.side)
        // RepositoryOpenViewModel shows the resolver
        is StatusAction.OpenInResolver -> Unit
        is StatusAction.CoAuthors -> when (val coAuthorAction = action.action) {
            CoAuthorAction.AddLine -> addCoAuthorLine()
            is CoAuthorAction.SuggestionsShown -> if (coAuthorAction.shown) {
                addCloseableView(CloseableView.COMMIT_FIELD_SUGGESTIONS)
            } else {
                removeCloseableView(CloseableView.COMMIT_FIELD_SUGGESTIONS)
            }

            CoAuthorAction.FieldLeft -> takeOutUntouchedCoAuthorLine()
        }

        StatusAction.ToggleSignOff -> toggleSignOff()
    }

    /** Fork-only: the commit message's "Add Signed-off-by" switches the repository's setting, and keeps its format. */
    private fun toggleSignOff() = viewModelScope.launch {
        val current = loadSignOffConfigUseCase().okOrNull()
            ?: SignOffConfig(SignOffConstants.DEFAULT_SIGN_OFF_ENABLED, SignOffConstants.DEFAULT_SIGN_OFF_FORMAT)

        saveSignOffConfigUseCase(current.copy(isEnabled = !current.isEnabled))
    }

    /**
     * Fork-only: "Add co-author" adds an empty `Co-authored-by` line where git puts trailers, with the caret at its end,
     * where the field suggests people. git places a marked trailer, and the marker shows where it went
     * ([withEmptyCoAuthorLine]).
     */
    private fun addCoAuthorLine() = viewModelScope.launch {
        val before = currentCommitMessage()
        val (after, caret) = withEmptyCoAuthorLine(addTrailerUseCase(before.text, MARKED_CO_AUTHOR_TRAILER))
            ?: withEmptyCoAuthorLine(appendTrailer(before.text, MARKED_CO_AUTHOR_TRAILER))
            ?: return@launch

        updateCommitMessage(TextFieldValue(after, TextRange(caret)))
        coAuthorInsertion.value = CoAuthorInsertion(++coAuthorInsertions, before, after)
    }

    /** Fork-only: Esc closed the co-author suggestions, which the field then hides until its text changes. */
    fun dismissCoAuthorSuggestions() {
        takeOutUntouchedCoAuthorLine()
        coAuthorDismissals.value++
    }

    /** Fork-only: the line that "Add co-author" added goes again when the message is still as the line left it. */
    private fun takeOutUntouchedCoAuthorLine() {
        val insertion = coAuthorInsertion.value ?: return
        coAuthorInsertion.value = null

        if (currentCommitMessage().text == insertion.after) {
            updateCommitMessage(insertion.before)
        }
    }

    /** Fork-only: the message as the field has it now, which [commitMessage] only follows a moment later. */
    private fun currentCommitMessage(): TextFieldValue = messageFlowOf(currentCommitMessageSource()).value

    fun rejectCommitterData() {
        this.committerDataRequestState.value = CommitterDataRequestState.Reject
    }

    fun acceptCommitterData(newAuthorInfo: AuthorInfo, persist: Boolean) {
        this.committerDataRequestState.value = CommitterDataRequestState.Accepted(newAuthorInfo, persist)
    }

    fun updateCommitMessage(message: TextFieldValue) {
        val source = currentCommitMessageSource()

        messageFlowOf(source).value = message

        persistMessage(message.text, source)
    }

    /** Fork-only: read now rather than from [commitMessageSource], which only follows while the pane is shown. */
    private fun currentCommitMessageSource(): CommitMessageSource {
        val repositoryState = repositoryDataRepository.repositoryState.value.dataOrNull()

        return commitMessageSource(repositoryState, squashMergeInProgress.value)
    }

    private fun persistMessage(newMessage: String?, source: CommitMessageSource) {
        // Fork-only: the message belongs to the worktree it's written in, even if the tab switches meanwhile
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return

        persistMessageJob?.cancel()

        persistMessageJob = viewModelScope.launch {
            delay(PERSIST_MESSAGE_DELAY_IN_MS.milliseconds)

            // Fork-only: a squash merge's message is in SQUASH_MSG, which neither JGit nor upstream wrote
            if (source == CommitMessageSource.SQUASH) {
                persistSquashMessageUseCase(repositoryPath, newMessage.orEmpty())
            } else {
                persistCommitMessageUseCase(repositoryPath, newMessage)
            }
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
        // Files with conflicts are left as they are (fork-only)
        val entries = selectedUnstagedDiffEntries.value.map { it.statusEntry }.selectionDiscard().entries

        if (entries.isNotEmpty()) {
            discardUnstaged(entries)
        }
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
        val entries = statusState.value.entriesShownUnder(path, entryType)

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


    private fun stageAll() = applyToAll(EntryType.UNSTAGED)

    private fun discardStaged(statusEntries: List<StatusEntry>) {
        discardEntriesUseCase(statusEntries, isStaged = true)
    }

    private fun discardUnstaged(statusEntries: List<StatusEntry>) {
        discardEntriesUseCase(statusEntries, isStaged = false)
    }

    private fun stage(statusEntry: StatusEntry) = stageUseCase(statusEntry)
    private fun unstage(statusEntry: StatusEntry) = unstageUseCase(statusEntry)

    private fun unstageAll() = applyToAll(EntryType.STAGED)

    /** The header button: the selection, the files that a search matches, or every file (see [BulkTarget]). */
    private fun applyToAll(entryType: EntryType) {
        val selected = when (entryType) {
            EntryType.STAGED -> selectedStagedDiffEntries
            EntryType.UNSTAGED -> selectedUnstagedDiffEntries
        }.value.map { it.statusEntry }

        val target = statusState.value.bulkTarget(entryType, selected)

        if (target.isNoOp) return

        when (entryType) {
            EntryType.STAGED -> unstageAllUseCase(target.entriesToApply)
            EntryType.UNSTAGED -> stageAllUseCase(target.entriesToApply)
        }
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
            // Fork-only: as for a commit, no empty Co-authored-by trailer
            message = withoutEmptyCoAuthorLines(message),
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

    fun commit(fieldMessage: String) = viewModelScope.launch {
        // Fork-only: a Co-authored-by line that names no one would be committed as an empty trailer
        val message = withoutEmptyCoAuthorLines(fieldMessage)
        coAuthorInsertion.value = null
        val amend = isAmend.value
        // Fork-only: the commit ends a merge, cherry-pick, revert or squash merge, so the field's source changes before
        // it returns
        val source = currentCommitMessageSource()

        val personIdent = getIdentity()

        // If someone clicks on commit before persisting the message (as it has a delay), cancel it.
        val hadOngoingPersistJob = persistMessageJob?.isActive == true
        persistMessageJob?.cancelAndJoin()

        // Fork-only: the commits of a merge, cherry-pick, revert or squash merge leave the draft, which they kept out
        // of view
        val doCommitResult = doCommitUseCase(
            message,
            amend,
            personIdent,
            isMergeMessage = source.isMergeMessage,
        ).await()

        if (doCommitResult is Either.Ok) {
            // Fork-only: clears the message that was committed. The commit removed its file, and clearing the field
            // the source has now would drop the draft that a merge, cherry-pick or revert had kept out of view.
            messageFlowOf(source).value = TextFieldValue("")
            isAmend.value = false
        } else {
            // If committing failed and the message was going to be persisted before the commit, restart the
            // persistence task
            if (hadOngoingPersistJob) {
                persistMessage(fieldMessage, source)
            }
        }
    }

    private suspend fun getIdentity(): Identity? {
        val author = when (val data = getAuthorUseCase()) {
            is Either.Err -> AuthorInfo(Identity(null, null), Identity(null, null))
            is Either.Ok -> data.value
        }

        // Fork-only: git may have an identity that JGit doesn't see (includeIf)
        return if (author.isIdentityMissing()) {
            committerDataRequestState.value = CommitterDataRequestState.WaitingInput(author)

            val committerData = committerDataRequestState.awaitAnswer()

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