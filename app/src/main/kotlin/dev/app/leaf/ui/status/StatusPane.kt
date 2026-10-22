@file:OptIn(ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package dev.app.leaf.ui.status

import dev.app.leaf.domain.worktrees.workTreeOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.systemSeparator
import dev.app.leaf.compose.rememberInTab
import dev.app.leaf.domain.cherrypick.CherryPickSequence
import dev.app.leaf.domain.rebase.SkippedRebaseCommit
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.commitmessage.ConventionalHistory
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.commitmessage.MessageStart
import dev.app.leaf.domain.commitmessage.canWrapBody
import dev.app.leaf.domain.commitmessage.commitMessageLayout
import dev.app.leaf.domain.extensions.parentDirectoryPath
import dev.app.leaf.domain.ignore.IgnoreRequest
import dev.app.leaf.domain.ignore.ignoreRequestFor
import dev.app.leaf.domain.ignore.ignoreRequestForFolder
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.sorting.DiscardedFiles
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.allDiscard
import dev.app.leaf.domain.sorting.folderDiscard
import dev.app.leaf.domain.sorting.selectionDiscard
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.icon
import dev.app.leaf.extensions.iconColor
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.RoleColors
import dev.app.leaf.theme.roleBorder
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.ChangedFilesList
import dev.app.leaf.ui.components.*
import dev.app.leaf.ui.components.sort.FilesSortMenuButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.context_menu.ConflictMenuActions
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.statusDirEntriesContextMenuItems
import dev.app.leaf.ui.context_menu.statusEntriesContextMenuItems
import dev.app.leaf.ui.context_menu.statusEntryContextMenuItems
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.dialogs.CommitAuthorDialog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun StatusPane(
    statusState: StatusState,
    onAction: (StatusAction) -> Unit,
    onBlameFile: (String) -> Unit,
    onHistoryFile: (String) -> Unit,
    /** Asks to discard the unstaged changes of a folder, as [discard] says. */
    onDiscardFolderChanges: (folderPath: String, discard: DiscardedFiles) -> Unit,
    /** Opens the ignore dialog for untracked files or a folder (fork-only). */
    onIgnore: (IgnoreRequest) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    completedTasks: StateFlow<List<CompletedTask>>,
) {
    val swapUncommittedChanges = statusState.swapUncommittedChanges
    // Kept for the tab, not for the lists' contents: staging a file must not scroll the lists back to the top
    val stagedListState = rememberInTab("statusStagedListState") {
        LazyListState()
    }
    val unstagedListState = rememberInTab("statusUnstagedListState") {
        LazyListState()
    }

    val commitMessage = statusState.commitMessage

    val isAmend = statusState.isAmend
    val isAmendRebaseInteractive = statusState.isAmendRebaseInteractive
    val committerDataRequestState = statusState.committerDataRequestState
    val selectedUnstagedDiffEntries = statusState.selectedUnstagedDiffEntries
    val selectedStagedDiffEntries = statusState.selectedStagedDiffEntries

    val showSearchStaged = statusState.showSearchStaged
    val searchFilterStaged = statusState.searchFilterStaged
    val showSearchUnstaged = statusState.showSearchUnstaged
    val searchFilterUnstaged = statusState.searchFilterUnstaged

    val isAmenableRebaseInteractive = statusState.isAmenableRebaseInteractive

    val doCommit = {
        onAction(StatusAction.Commit(commitMessage.text))
        Unit
    }

    // Fork-only: git refuses to commit while files have conflicts, as after a squash merge that stopped at some
    val canCommit = statusState.canCommit
    val canAmend = statusState.canAmend
    // Fork-only: what stops the primary button, and what the field says about it (see FinishBlockers.kt)
    val finishBlocker = statusState.finishBlocker()
    val finishHint = statusState.finishHint()
    val showsContinue = statusState.showsContinue()
    val tabFocusRequester = LocalTabFocusRequester.current

    // Only when a search opens or closes, not at each change of the state, such as each letter typed in the search
    LaunchedEffect(statusState.showSearchUnstaged, statusState.showSearchStaged) {
        launch {
            if (statusState.showSearchUnstaged || statusState.showSearchStaged) {
                tabFocusRequester.requestFocus()
            }
        }
    }

    if (committerDataRequestState is CommitterDataRequestState.WaitingInput) {
        CommitAuthorDialog(
            committerDataRequestState.authorInfo,
            onClose = { onAction(StatusAction.RejectCommitterData) },
            onAccept = { newAuthorInfo, persist ->
                onAction(StatusAction.AcceptCommitterData(newAuthorInfo, persist))
            },
        )
    }

    // A drag changes this copy at once; the saved sizes only change when the drag ends, and come back through the state.
    var sectionSizes by remember { mutableStateOf(statusState.sectionSizes) }

    LaunchedEffect(statusState.sectionSizes) {
        sectionSizes = statusState.sectionSizes
    }

    Column(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = statusState.isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colors.primaryVariant)
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f),
        ) {
            val paneHeight = maxHeight.value
            val heights = sectionSizes.fitTo(paneHeight)
            val stagedOnTop = !swapUncommittedChanges

            fun saveSectionSizes(sizes: StatusSectionSizes) {
                sectionSizes = sizes
                onAction(StatusAction.SectionSizesChanged(sizes))
            }

            @Composable
            fun staged() {
                StatusChangesList(
                    entryType = EntryType.STAGED,
                    statusState,
                    showSearchStaged,
                    searchFilter = searchFilterStaged,
                    listState = stagedListState,
                    selectedEntries = selectedStagedDiffEntries,
                    onSearchFilterToggled = { onAction(StatusAction.SearchFilterToggledStaged(it)) },
                    onSearchFocused = { onAction(StatusAction.AddStagedSearchToCloseableView) },
                    onBlameFile = onBlameFile,
                    onHistoryFile = onHistoryFile,
                    onDiscardFolderChanges = onDiscardFolderChanges,
                    onIgnore = onIgnore,
                    onConfirmAction = onConfirmAction,
                    onAction = { onAction(it) },
                    modifier = Modifier.height(heights.staged.dp),
                )
            }

            @Composable
            fun unstaged() {
                StatusChangesList(
                    entryType = EntryType.UNSTAGED,
                    statusState = statusState,
                    showSearch = showSearchUnstaged,
                    searchFilter = searchFilterUnstaged,
                    listState = unstagedListState,
                    selectedEntries = selectedUnstagedDiffEntries,
                    onSearchFilterToggled = { onAction(StatusAction.SearchFilterToggledUnstaged(it)) },
                    onSearchFocused = { onAction(StatusAction.AddUnstagedSearchToCloseableView) },
                    onBlameFile = onBlameFile,
                    onHistoryFile = onHistoryFile,
                    onDiscardFolderChanges = onDiscardFolderChanges,
                    onIgnore = onIgnore,
                    onConfirmAction = onConfirmAction,
                    onAction = { onAction(it) },
                    modifier = Modifier.height(heights.unstaged.dp),
                )
            }

            Column {
                if (stagedOnTop) staged() else unstaged()

                SectionDivider(
                    onDrag = { delta ->
                        sectionSizes = sectionSizes.withListsDividerMoved(paneHeight, delta, stagedOnTop)
                    },
                    onDragStopped = { saveSectionSizes(sectionSizes) },
                    onDoubleClick = { saveSectionSizes(sectionSizes.copy(stagedShare = STATUS_DEFAULT_STAGED_SHARE)) },
                )

                if (stagedOnTop) unstaged() else staged()

                SectionDivider(
                    onDrag = { delta ->
                        sectionSizes = sectionSizes.withCommitDividerMoved(paneHeight, delta, stagedOnTop)
                    },
                    onDragStopped = { saveSectionSizes(sectionSizes) },
                    onDoubleClick = {
                        saveSectionSizes(sectionSizes.copy(commitFieldHeight = STATUS_DEFAULT_COMMIT_FIELD_HEIGHT))
                    },
                )

                CommitField(
                    canCommit,
                    isAmend,
                    canAmend,
                    doCommit,
                    commitMessage,
                    previousCommitMessage = statusState.previousCommitMessage,
                    statusState.repositoryState,
                    isAmenableRebaseInteractive,
                    showsContinue,
                    finishBlocker,
                    isAmendRebaseInteractive,
                    finishHint,
                    statusState.isLoading,
                    setCommitMessage = {
                        onAction(StatusAction.UpdateCommitMessage(it))
                    },
                    // The same as the banner's Abort (fork-only)
                    onResetRepoState = { abortOperationInProgress(statusState, onAction, onConfirmAction) },
                    onAbortRebase = { abortOperationInProgress(statusState, onAction, onConfirmAction) },
                    onAmendChecked = { amend ->
                        if (amend && commitMessage.text.isEmpty()) {
                            onAction(StatusAction.UpdateCommitMessage(TextFieldValue(statusState.previousCommitMessage.orEmpty())))
                        }
                        onAction(StatusAction.ToggleAmend(amend))
                    },
                    onContinueRebase = { onAction(StatusAction.ContinueRebase(commitMessage.text)) },
                    onSkipRebase = {
                        // Fork-only: names the commit; one with changes is listed once the rebase ends
                        val stop = statusState.rebaseStop
                        val discards = statusState.skipDiscards
                        val skipped = stop
                            ?.takeIf { discards > 0 }
                            ?.let { SkippedRebaseCommit(it.commit, it.subject, it.branch) }

                        onConfirmAction(
                            ConfirmableAction.SkipRebaseCommit(stop?.shortHash, stop?.subject, stop?.branch, discards)
                        ) {
                            onAction(StatusAction.SkipRebase(skipped))
                        }
                    },
                    onAmendRebaseInteractiveChecked = { amend ->
                        if (amend && commitMessage.text.isEmpty()) {
                            onAction(StatusAction.UpdateCommitMessage(TextFieldValue(statusState.previousCommitMessage.orEmpty())))
                        }

                        onAction(StatusAction.ToggleAmendRebaseInteractive(amend))
                    },
                    // Fork-only: the title row comes on top of the saved field height
                    modifier = Modifier.height(heights.commitSection.dp),
                    messageCleanup = statusState.messageCleanup,
                    messageHints = statusState.messageHints,
                    coAuthors = statusState.coAuthors,
                    onCoAuthorAction = { onAction(StatusAction.CoAuthors(it)) },
                    isSignOff = statusState.isSignOff,
                    onToggleSignOff = { onAction(StatusAction.ToggleSignOff) },
                    conventionalHistory = statusState.conventionalHistory,
                    commitlintRules = statusState.commitlintRules,
                    messageStart = statusState.messageStart,
                    cherryPickSequence = statusState.cherryPickSequence,
                    canSkipCherryPick = statusState.canSkipCherryPick,
                    canSkipRebaseCommit = statusState.canSkipRebaseCommit,
                    rebaseSkipOtherChanges = statusState.rebaseSkipOtherChanges,
                    onContinueCherryPick = { onAction(StatusAction.ContinueCherryPick(commitMessage.text)) },
                    onSkipCherryPick = {
                        onConfirmAction(ConfirmableAction.SkipCherryPickCommit(statusState.skipDiscards)) {
                            onAction(StatusAction.SkipCherryPick)
                        }
                    },
                    scopeKey = statusState.scopeKey,
                )
            }
        }
    }
}

@Composable
fun StatusChangesList(
    entryType: EntryType,
    statusState: StatusState,
    showSearch: Boolean,
    searchFilter: TextFieldValue,
    listState: LazyListState,
    selectedEntries: List<DiffType.UncommittedDiff>,
    onSearchFilterToggled: (Boolean) -> Unit,
    onSearchFocused: () -> Unit,
    onBlameFile: (String) -> Unit,
    onHistoryFile: (String) -> Unit,
    onDiscardFolderChanges: (folderPath: String, discard: DiscardedFiles) -> Unit,
    /** Opens the ignore dialog for untracked files or a folder (fork-only). */
    onIgnore: (IgnoreRequest) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    onAction: (StatusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val title = when (entryType) {
        EntryType.STAGED -> stringResource(Res.string.uncommited_changes_staged_title)
        EntryType.UNSTAGED -> stringResource(Res.string.uncommited_changes_unstaged_title)
    }

    // Fork-only: with a search narrowing the list, the button says how many files it acts on
    val bulkTarget = statusState.bulkTarget(entryType, selectedEntries.map { it.statusEntry })
    val actionInfo = getActionInfo(entryType).withMatches(entryType, bulkTarget)
    val rows = when (entryType) {
        EntryType.STAGED -> statusState.stagedRows
        EntryType.UNSTAGED -> statusState.unstagedRows
    }

    // Unstaged only, and only for a folder with something to restore
    val folderDiscardAction: (folderPath: String) -> (() -> Unit)? = { folderPath ->
        val discard = statusState.entriesShownUnder(folderPath, entryType).folderDiscard()

        if (entryType == EntryType.UNSTAGED && discard.entries.isNotEmpty()) {
            { onDiscardFolderChanges(folderPath, discard) }
        } else {
            null
        }
    }

    // Fork-only, Unstaged only: "Discard all" acts on the files that the list shows, the search matches while it searches
    val search = statusState.activeSearchOf(entryType)
    val discardAllLabel = stringResource(Res.string.status_header_discard_all)
    val nothingToDiscardLabel = stringResource(Res.string.status_header_nothing_to_discard)
    val moreItems = if (entryType == EntryType.UNSTAGED) {
        val plan = remember(statusState.unstaged, search) { statusState.entriesShown(entryType).allDiscard() }

        if (plan.hasWork) {
            listOf(
                SortMenuItem.Option(
                    label = discardAllLabel,
                    isChecked = false,
                    closesMenu = true,
                    isDestructive = true,
                    icon = Res.drawable.undo,
                ) {
                    val action = ConfirmableAction.DiscardAll(plan, search)

                    onConfirmAction(action) {
                        onAction(StatusAction.DiscardAll(plan, action.deleteNewFiles.isChecked))
                    }
                }
            )
        } else {
            listOf(SortMenuItem.Note(nothingToDiscardLabel))
        }
    } else {
        emptyList()
    }

    // Unstaged only, and only for a folder that shows a new (untracked) file, which is what ignoring hides
    val folderIgnoreAction: (folderPath: String) -> (() -> Unit)? = { folderPath ->
        ignoreRequestForFolder(statusState.entriesShownUnder(folderPath, entryType), entryType, folderPath)
            ?.let { request -> { onIgnore(request) } }
    }

    ChangesList(
        title = title,
        actionInfo = actionInfo,
        allActionEnabled = !bulkTarget.isNoOp,
        entryType = entryType,
        rows = rows,
        moreItems = moreItems,
        folderDiscardAction = folderDiscardAction,
        folderIgnoreAction = folderIgnoreAction,
        onIgnore = onIgnore,
        viewState = statusState.viewState,
        showSearch = showSearch,
        searchFilter = searchFilter,
        listState = listState,
        selectedEntries = selectedEntries,
        onSearchFilterToggled = onSearchFilterToggled,
        onSearchFocused = onSearchFocused,
        onBlameFile = onBlameFile,
        onHistoryFile = onHistoryFile,
        onDeleteFile = { statusEntry ->
            if (statusEntry.nestedRepository == NestedRepository.LINKED_WORKTREE) {
                // Refused with an explanation, so there is nothing to confirm (fork-only)
                onAction(StatusAction.Delete(statusEntry))
            } else {
                onConfirmAction(ConfirmableAction.DeleteFile(statusEntry)) { onAction(StatusAction.Delete(statusEntry)) }
            }
        },
        onDiscardFile = { statusEntry ->
            onConfirmAction(ConfirmableAction.DiscardFile(statusEntry)) { onAction(StatusAction.Reset(statusEntry)) }
        },
        onDiscardSelected = {
            // Files with conflicts are left as they are (fork-only)
            val discard = selectedEntries.map { it.statusEntry }.selectionDiscard()
            val action = ConfirmableAction.DiscardFiles(discard.entries.size, entryType, discard.keptConflicted)

            onConfirmAction(action) {
                onAction(StatusAction.DiscardSelected(entryType))
            }
        },
        onUseConflictSide = { entries, side ->
            onConfirmAction(ConfirmableAction.UseConflictSide(entries, side, statusState.conflictSides)) {
                onAction(StatusAction.UseConflictSide(entries.map { it.filePath }, side))
            }
        },
        onAction = onAction,
        onCopy = { relative, entries ->
            scope.launch {
                copyEntriesPath(clipboard, entries, relative, statusState.repositoryPath)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun ChangesList(
    title: String,
    actionInfo: ActionInfo,
    /** False while a search matches nothing: the all-files button does nothing (fork-only). */
    allActionEnabled: Boolean = true,
    entryType: EntryType,
    rows: List<FileRow<StatusEntry>>,
    /** The items of the header's "⋯" menu, none for no menu (fork-only). */
    moreItems: List<SortMenuItem>,
    /** What discarding a folder does, or null when its menu shouldn't offer it. */
    folderDiscardAction: (folderPath: String) -> (() -> Unit)?,
    /** Opens the ignore dialog for a folder, or null when its menu shouldn't offer it (fork-only). */
    folderIgnoreAction: (folderPath: String) -> (() -> Unit)?,
    /** Opens the ignore dialog for untracked files (fork-only). */
    onIgnore: (IgnoreRequest) -> Unit,
    viewState: FilesViewState,
    showSearch: Boolean,
    searchFilter: TextFieldValue,
    listState: LazyListState,
    selectedEntries: List<DiffType.UncommittedDiff>,
    onSearchFilterToggled: (Boolean) -> Unit,
    onSearchFocused: () -> Unit,
    onBlameFile: (String) -> Unit,
    onHistoryFile: (String) -> Unit,
    onDeleteFile: (StatusEntry) -> Unit,
    onDiscardFile: (StatusEntry) -> Unit,
    /** Discards the selected files. */
    onDiscardSelected: () -> Unit,
    /** Asks to resolve conflicted files with one side (fork-only). */
    onUseConflictSide: (List<StatusEntry>, ConflictSide) -> Unit,
    onAction: (StatusAction) -> Unit,
    onCopy: (relative: Boolean, entries: List<StatusEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun entriesContextMenu(): (StatusEntry) -> List<ContextMenuElement> = { statusEntry ->
        statusEntryContextMenuItems(
            statusEntry = statusEntry,
            entryType = entryType,
            onBlame = { onBlameFile(statusEntry.filePath) },
            onHistory = { onHistoryFile(statusEntry.filePath) },
            onReset = { onDiscardFile(statusEntry) },
            onDelete = { onDeleteFile(statusEntry) },
            onOpenFileInFolder = { onAction(StatusAction.OpenInFolder(statusEntry.parentDirectoryPath)) },
            onIgnore = { onIgnore(IgnoreRequest.Files(listOf(statusEntry.filePath))) },
            onCopyFilePath = { relative ->
                onCopy(
                    relative,
                    listOf(statusEntry),
                )
            },
            conflictActions = ConflictMenuActions(
                onOpenInExternalMergeTool = { onAction(StatusAction.OpenInExternalMergeTool(statusEntry)) },
                onUseSide = { side -> onUseConflictSide(listOf(statusEntry), side) },
                onResolve = { onAction(StatusAction.OpenInResolver(statusEntry)) },
            ),
        )
    }

    fun selectedEntriesContextMenu(): (StatusEntry) -> List<ContextMenuElement> = {
        val discard = selectedEntries.map { it.statusEntry }.selectionDiscard()
        // Fork-only: the untracked files among them, which the ignore dialog takes
        val ignore = ignoreRequestFor(selectedEntries.map { it.statusEntry }, entryType)

        statusEntriesContextMenuItems(
            selectedEntriesCount = selectedEntries.count(),
            entryType = entryType,
            onDiscard = onDiscardSelected.takeIf { discard.entries.isNotEmpty() },
            discardCount = discard.entries.size,
            onStageSelected = { onAction(StatusAction.SelectedEntriesAction(EntryType.UNSTAGED)) },
            onUnstageSelected = { onAction(StatusAction.SelectedEntriesAction(EntryType.STAGED)) },
            onCopyFilesPath = { relative ->
                onCopy(
                    relative,
                    selectedEntries.map { it.statusEntry },
                )
            },
            onUseConflictSide = selectedEntries.map { it.statusEntry }
                .takeIf { entries -> entries.all { it.conflict != null } }
                ?.let { entries -> { side -> onUseConflictSide(entries, side) } },
            ignoreCount = ignore?.paths?.size ?: 0,
            onIgnore = ignore?.let { request -> { onIgnore(request) } },
        )
    }

    val showActionForSelected = remember(selectedEntries) { selectedEntries.count() > 1 }
    val selectedStatusEntries = remember(selectedEntries) { selectedEntries.map { it.statusEntry } }
    val keyboardModifiers = LocalWindowInfo.current.keyboardModifiers

    fun selectEntry(statusEntry: StatusEntry, isCtrlPressed: Boolean, isMetaPressed: Boolean, isShiftPressed: Boolean) {
        onAction(
            StatusAction.SelectEntry(
                statusEntry = statusEntry,
                isCtrlPressed = isCtrlPressed,
                isMetaPressed = isMetaPressed,
                isShiftPressed = isShiftPressed,
                diffEntries = rows.mapNotNull { (it as? FileRow.File)?.file?.item },
                selectedEntries = selectedEntries,
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        FilesChangedHeader(
            title = title,
            actionInfo = actionInfo,
            onAllAction = { onAction(StatusAction.AllEntriesAction(entryType)) },
            allActionEnabled = allActionEnabled,
            moreItems = moreItems,
            sortAction = {
                FilesSortMenuButton(
                    viewState = viewState,
                    onViewStateChange = { onAction(StatusAction.ViewStateChanged(it)) },
                )
            },
            searchFilter = searchFilter,
            onSearchFilterChanged = { onAction(StatusAction.SearchFilterChanged(it, entryType)) },
            onSearchFilterToggled = onSearchFilterToggled,
            onSearchFocused = onSearchFocused,
            showSearch = showSearch,
            showActionForSelected = showActionForSelected,
        )

        ChangedFilesList(
            rows = rows,
            viewState = viewState,
            selectedEntries = selectedStatusEntries,
            listState = listState,
            resetKey = null,
            modifier = Modifier.background(MaterialTheme.colors.background),
            fileIcon = { it.icon },
            fileIconColor = { it.iconColor },
            onFileClick = { statusEntry ->
                selectEntry(
                    statusEntry,
                    isCtrlPressed = keyboardModifiers.isCtrlPressed,
                    isMetaPressed = keyboardModifiers.isMetaPressed,
                    isShiftPressed = keyboardModifiers.isShiftPressed,
                )
            },
            onKeyboardSelect = { statusEntry ->
                selectEntry(statusEntry, isCtrlPressed = false, isMetaPressed = false, isShiftPressed = false)
            },
            onFolderToggle = { path -> onAction(StatusAction.TreeDirectoryToggle(path, entryType)) },
            onSplitRatioChange = { onAction(StatusAction.ViewStateChanged(viewState.copy(splitRatio = it))) },
            onGenerateContextMenu = { statusEntry ->
                if (selectedEntries.count() > 1 && statusEntry in selectedStatusEntries) {
                    selectedEntriesContextMenu()(statusEntry)
                } else {
                    entriesContextMenu()(statusEntry)
                }
            },
            onFileDoubleClick = { statusEntry -> onAction(StatusAction.EntryAction(statusEntry)) },
            onGenerateFolderContextMenu = { folder ->
                statusDirEntriesContextMenuItems(
                    entryType = entryType,
                    onStageChanges = { onAction(StatusAction.DirectoryAction(folder.path, entryType)) },
                    onDiscardDirectoryChanges = folderDiscardAction(folder.path),
                    onIgnoreFolder = folderIgnoreAction(folder.path),
                )
            },
            fileTrailingAction = { statusEntry, isHovered ->
                EntryHoverAction(isHovered, actionInfo) { onAction(StatusAction.EntryAction(statusEntry)) }
            },
            folderTrailingAction = { folder, isHovered ->
                EntryHoverAction(isHovered, actionInfo) {
                    onAction(StatusAction.FolderRowAction(folder.path, entryType))
                }
            },
        )
    }
}

/** The Stage or Unstage button shown at the end of a hovered file or folder. */
@Composable
private fun BoxScope.EntryHoverAction(isHovered: Boolean, actionInfo: ActionInfo, onClick: () -> Unit) {
    AnimatedVisibility(
        modifier = Modifier
            .align(Alignment.CenterEnd),
        visible = isHovered,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        SecondaryButton(
            onClick = onClick,
            text = actionInfo.applyToOneTitle,
            colors = actionInfo.colors,
            modifier = Modifier
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
internal fun CommitField(
    canCommit: Boolean,
    isAmend: Boolean,
    canAmend: Boolean,
    doCommit: () -> Unit,
    commitMessage: TextFieldValue,
    previousCommitMessage: String?,
    repositoryState: RepositoryState,
    isAmenableRebaseInteractive: Boolean,
    showsContinue: Boolean,
    finishBlocker: FinishBlocker?,
    isAmendRebaseInteractive: Boolean,
    finishHint: FinishBlocker?,
    isLoading: Boolean,
    setCommitMessage: (TextFieldValue) -> Unit,
    onResetRepoState: () -> Unit,
    onAbortRebase: () -> Unit,
    onContinueRebase: () -> Unit,
    onSkipRebase: () -> Unit,
    onAmendChecked: (Boolean) -> Unit,
    onAmendRebaseInteractiveChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** How git cleans the message up, which the field shows (fork-only). */
    messageCleanup: MessageCleanup = MessageCleanup.DEFAULT,
    /** The subject's count and tint, and the ruler, as the settings say (fork-only). */
    messageHints: CommitMessageHints = CommitMessageHints(),
    /** Whom to suggest as co-authors, and the line that "Add co-author" added (fork-only). */
    coAuthors: CoAuthorState = CoAuthorState(),
    onCoAuthorAction: (CoAuthorAction) -> Unit = {},
    /** The repository's sign-off setting, which the menu's "Add Signed-off-by" switches (fork-only). */
    isSignOff: Boolean = false,
    onToggleSignOff: () -> Unit = {},
    /** The types and scopes that the history uses, for the Conventional Commits suggestions (fork-only). */
    conventionalHistory: ConventionalHistory = ConventionalHistory(),
    /** The worktree's commitlint config, which narrows them (fork-only). */
    commitlintRules: CommitlintRules? = null,
    /** What Tab writes into the empty field: the branch's ticket key and the commit template (fork-only). */
    messageStart: MessageStart? = null,
    /** The branch's ticket key as the first scope suggestion, where the key doesn't go in front of the subject (fork-only). */
    scopeKey: String? = null,
    /** A cherry-pick of several commits that git stopped, whose buttons are Continue or Skip, and Abort (fork-only). */
    cherryPickSequence: CherryPickSequence? = null,
    /** Skip next to Continue, for a commit that git stopped at with conflicts (fork-only). */
    canSkipCherryPick: Boolean = false,
    /** The same for a rebase, blocked while [rebaseSkipOtherChanges] files outside the commit have changes (fork-only). */
    canSkipRebaseCommit: Boolean = false,
    rebaseSkipOtherChanges: Int = 0,
    onContinueCherryPick: () -> Unit = {},
    onSkipCherryPick: () -> Unit = {},
) {
    val isReadOnlyRebase = repositoryState.isRebasing && !isAmenableRebaseInteractive
    // Fork-only: the subject's length and whether it runs on, from the message as git will commit it. A read-only field
    // shows the last commit's message, which isn't committed again.
    val messageLayout = remember(commitMessage.text, messageCleanup) {
        commitMessageLayout(commitMessage.text, messageCleanup)
    }.takeUnless { isReadOnlyRebase }
    // Fork-only: suggestions while the caret is on a Co-authored-by line (CoAuthorSuggestions.kt), or typing a
    // Conventional Commits type or scope at the subject's start (CommitTypeSuggestions.kt). Esc hides them, through the
    // tab, until the text changes.
    val fieldFocusRequester = remember { FocusRequester() }
    var isFieldFocused by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0) }
    var dismissedText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(coAuthors.dismissals) {
        if (coAuthors.dismissals > 0) {
            dismissedText = commitMessage.text
        }
    }

    val suggests = !isReadOnlyRebase && isFieldFocused && dismissedText != commitMessage.text
    val coAuthorSuggestion = remember(commitMessage, coAuthors.candidates) {
        coAuthorSuggestion(commitMessage, coAuthors.candidates)
    }.takeIf { suggests }
    val typeSuggestion = remember(commitMessage, conventionalHistory, messageHints.conventionalCommits, commitlintRules, scopeKey) {
        commitTypeSuggestion(commitMessage, conventionalHistory, messageHints.conventionalCommits, commitlintRules, scopeKey)
    }.takeIf { suggests && coAuthorSuggestion == null }
    // Fork-only: the branch's ticket key and the commit template, which the empty field shows and Tab writes
    val offeredStart = messageStart.takeIf { !isReadOnlyRebase && isFieldFocused && commitMessage.text.isEmpty() }
    // Fork-only: "Wrap body at 72 columns" is greyed out while every line fits
    val canWrapBody = remember(commitMessage.text, messageCleanup) { canWrapBody(commitMessage.text, messageCleanup) }
    val suggestionCount = coAuthorSuggestion?.choices?.size ?: typeSuggestion?.choices?.size
    val typed = coAuthorSuggestion?.line?.value ?: typeSuggestion?.typed
    var highlighted by remember(coAuthorSuggestion?.choices, typeSuggestion?.choices, typed.isNullOrEmpty()) {
        mutableStateOf(firstHighlighted(typed))
    }
    val showsSuggestions = suggestionCount != null

    fun pickSuggestion(index: Int) {
        when {
            coAuthorSuggestion != null ->
                setCommitMessage(commitMessage.withCoAuthor(coAuthorSuggestion, coAuthorSuggestion.choices[index]))

            typeSuggestion != null ->
                setCommitMessage(commitMessage.withConventionalChoice(typeSuggestion, typeSuggestion.choices[index]))
        }
    }

    DisposableEffect(showsSuggestions) {
        onCoAuthorAction(CoAuthorAction.SuggestionsShown(showsSuggestions))

        onDispose {
            if (showsSuggestions) {
                onCoAuthorAction(CoAuthorAction.SuggestionsShown(false))
            }
        }
    }

    // "Add co-author" came from the menu, which had the focus: the caret waits in the new line
    LaunchedEffect(coAuthors.insertion?.id) {
        if (coAuthors.insertion != null) {
            fieldFocusRequester.requestFocus()
        }
    }

    // Fork-only: a merge's, cherry-pick's or revert's primary button follows finishBlocker, and the key does what it
    // does. A revert's also waits for the status to load.
    val finishEnabled = finishBlocker == null && !(repositoryState.isReverting && isLoading)
    val acceptsCommit = if (cherryPickSequence != null) {
        // Fork-only: the key continues, as the button does
        showsContinue && finishEnabled
    } else if (repositoryState.isMerging || repositoryState.isCherryPicking || repositoryState.isReverting) {
        finishEnabled
    } else {
        canCommit || isAmend && canAmend
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // Fork-only (upstream issue 344): a title row like the lists' headers, and a field with a hint, not a label
        CommitMessageTitle(
            header = commitMessageHeader(
                repositoryState,
                isAmenableRebaseInteractive,
                isAmend,
                isAmendRebaseInteractive,
                isSignOff = isSignOff,
                canWrapBody = canWrapBody,
            ),
            onToggleAmend = {
                if (repositoryState.isRebasing) onAmendRebaseInteractiveChecked(!isAmendRebaseInteractive) else onAmendChecked(!isAmend)
            },
            subjectCount = messageLayout?.let { subjectCount(it, messageHints) },
            onAddCoAuthor = { onCoAuthorAction(CoAuthorAction.AddLine) },
            onToggleSignOff = onToggleSignOff,
            onWrapBody = { setCommitMessage(commitMessage.withWrappedBody(messageCleanup)) },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(weight = 1f, fill = true),
        ) {
            CommitMessageTextField(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(fieldFocusRequester)
                    .onFocusChanged { focusState ->
                        if (isFieldFocused && !focusState.isFocused) {
                            onCoAuthorAction(CoAuthorAction.FieldLeft)
                        }

                        isFieldFocused = focusState.isFocused
                    }
                    .onGloballyPositioned { fieldWidth = it.size.width }
                    .onPreviewKeyEvent { keyEvent ->
                        val key = suggestionCount?.let { suggestionKey(keyEvent, highlighted, it) }

                        when {
                            key is SuggestionKey.Highlight -> {
                                highlighted = key.index
                                true
                            }

                            key is SuggestionKey.Pick -> {
                                pickSuggestion(key.index)
                                true
                            }

                            // Fork-only: Tab writes the start that the empty field shows
                            isPlainTab(keyEvent) && offeredStart != null -> {
                                setCommitMessage(offeredStart.toFieldValue())
                                true
                            }

                            // Fork-only: Enter continues or ends a list of co-authors, as an editor's list
                            isPlainEnter(keyEvent) && commitMessage.afterCoAuthorEnter() != null -> {
                                setCommitMessage(checkNotNull(commitMessage.afterCoAuthorEnter()))
                                true
                            }

                            keyEvent.matchesBinding(KeybindingOption.TEXT_ACCEPT) && acceptsCommit -> {
                                if (cherryPickSequence != null) onContinueCherryPick() else doCommit()
                                true
                            }

                            else -> false
                        }
                    },
                value = if (isReadOnlyRebase) TextFieldValue(previousCommitMessage.orEmpty()) else commitMessage,
                onValueChange = setCommitMessage,
                enabled = !repositoryState.isRebasing || isAmenableRebaseInteractive,
                cleanup = messageCleanup,
                hints = messageHints,
                startGhost = offeredStart?.text,
            )

            val suggestionsWidth = with(LocalDensity.current) { fieldWidth.toDp() }

            if (coAuthorSuggestion != null) {
                CoAuthorSuggestionsPopup(
                    suggestion = coAuthorSuggestion,
                    highlighted = highlighted,
                    width = suggestionsWidth,
                    onHighlight = { highlighted = it },
                    onPick = { setCommitMessage(commitMessage.withCoAuthor(coAuthorSuggestion, it)) },
                )
            } else if (typeSuggestion != null) {
                CommitTypeSuggestionsPopup(
                    suggestion = typeSuggestion,
                    highlighted = highlighted,
                    width = suggestionsWidth,
                    onHighlight = { highlighted = it },
                    onPick = { setCommitMessage(commitMessage.withConventionalChoice(typeSuggestion, it)) },
                )
            }
        }

        // Fork-only: what Tab writes into the empty field
        if (offeredStart != null) {
            MessageStartHint(offeredStart)
        }

        // Fork-only: a subject that runs on into the next line, which git log would show as one
        if (messageLayout?.blankLineAt != null) {
            SubjectRunsOnHint(
                onInsertBlankLine = { setCommitMessage(commitMessage.withBlankLineAfterSubject(messageCleanup)) },
            )
        }

        // Fork-only: why the primary button below is disabled
        if (finishHint != null) {
            FinishHint(finishHint)
        }

        when {
            // Fork-only: git's sequencer goes on with the commits left, so Continue rather than Commit
            cherryPickSequence != null -> StoppedOperationButtons(
                showsContinue = showsContinue,
                continueEnabled = finishEnabled,
                canSkip = canSkipCherryPick,
                onAbort = onResetRepoState,
                onContinue = onContinueCherryPick,
                onSkip = onSkipCherryPick,
            )

            repositoryState.isMerging -> MergeButtons(
                enabled = finishEnabled,
                onAbort = onResetRepoState,
                onMerge = { doCommit() }
            )

            // Fork-only: Skip next to Continue too, as for a cherry-pick, but never over other files' changes
            repositoryState.isRebasing -> StoppedOperationButtons(
                showsContinue = showsContinue,
                continueEnabled = finishEnabled,
                canSkip = canSkipRebaseCommit,
                skipBlockedBy = rebaseSkipOtherChanges,
                onAbort = onAbortRebase,
                onContinue = onContinueRebase,
                onSkip = onSkipRebase,
            )

            repositoryState.isCherryPicking -> CherryPickingButtons(
                enabled = finishEnabled,
                onAbort = onResetRepoState,
                onCommit = {
                    doCommit()
                }
            )

            repositoryState.isReverting -> RevertingButtons(
                enabled = finishEnabled,
                onAbort = onResetRepoState,
                onCommit = {
                    doCommit()
                }
            )

            else -> UncommittedChangesButtons(
                canCommit = canCommit,
                canAmend = canAmend,
                isAmend = isAmend,
                onCommit = doCommit,
            )
        }
    }
}

@Composable
fun UncommittedChangesButtons(
    canCommit: Boolean,
    canAmend: Boolean,
    isAmend: Boolean,
    onCommit: () -> Unit,
) {
    val buttonText = if (isAmend)
        stringResource(Res.string.uncommited_changes_primary_button_amend)
    else
        stringResource(Res.string.uncommited_changes_primary_button_commit)

    Column {
        // Fork-only: Amend is in the commit message title row's menu now (upstream issue 344)
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
        ) {
            ConfirmationButton(
                text = buttonText,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                onClick = {
                    onCommit()
                },
                enabled = canCommit || (canAmend && isAmend),
                shape = RoundedCornerShape(4.dp)
            )
        }
    }
}

@Composable
fun MergeButtons(
    enabled: Boolean,
    onAbort: () -> Unit,
    onMerge: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(36.dp)
    ) {
        AbortButton(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
                .fillMaxHeight(),
            onClick = onAbort
        )

        ConfirmationButton(
            text = stringResource(Res.string.uncommited_changes_primary_button_merge),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .fillMaxHeight(),
            enabled = enabled,
            onClick = onMerge,
        )
    }
}

@Composable
fun CherryPickingButtons(
    enabled: Boolean,
    onAbort: () -> Unit,
    onCommit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(36.dp)
    ) {
        AbortButton(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
                .fillMaxHeight(),
            onClick = onAbort
        )

        ConfirmationButton(
            text = stringResource(Res.string.uncommited_changes_primary_button_commit),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .fillMaxHeight(),
            enabled = enabled,
            onClick = onCommit,
        )
    }
}

@Composable
fun RevertingButtons(
    enabled: Boolean,
    onAbort: () -> Unit,
    onCommit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(36.dp)
    ) {
        AbortButton(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
            onClick = onAbort
        )

        ConfirmationButton(
            text = stringResource(Res.string.uncommited_changes_primary_button_continue),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .fillMaxHeight(),
            enabled = enabled,
            onClick = onCommit,
        )
    }
}

/**
 * A rebase, or a cherry-pick of several commits, that git stopped at a commit (fork-only): Abort, then Continue, or Skip
 * where the stopped commit came out empty. Where Skip can leave the commit out whatever its conflicts ([canSkip]), it
 * comes between them too. [skipBlockedBy] files outside the commit have changes that Skip would lose: it's disabled then,
 * and its tooltip says why.
 */
@Composable
fun StoppedOperationButtons(
    showsContinue: Boolean,
    continueEnabled: Boolean,
    canSkip: Boolean,
    onAbort: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    skipBlockedBy: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(36.dp)
    ) {
        AbortButton(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
                .fillMaxHeight(),
            onClick = onAbort
        )

        if (showsContinue && canSkip) {
            val blockedReason = if (skipBlockedBy > 0) {
                pluralStringResource(Res.plurals.skip_blocked_other_changes, skipBlockedBy, skipBlockedBy)
            } else {
                null
            }

            DelayedTooltip(
                text = blockedReason,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .fillMaxHeight(),
            ) {
                RoleButton(
                    text = stringResource(Res.string.uncommited_changes_primary_button_skip),
                    colors = MaterialTheme.styleColors.neutral,
                    modifier = Modifier.fillMaxSize(),
                    onClick = onSkip,
                    enabled = blockedReason == null,
                )
            }
        }

        ConfirmationButton(
            text = if (showsContinue) {
                stringResource(Res.string.uncommited_changes_primary_button_continue)
            } else {
                stringResource(Res.string.uncommited_changes_primary_button_skip)
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .fillMaxHeight(),
            enabled = !showsContinue || continueEnabled,
            onClick = if (showsContinue) onContinue else onSkip,
        )
    }
}

@Composable
fun AbortButton(modifier: Modifier, onClick: () -> Unit) {
    // Fork-only: the style's destructive button
    RoleButton(
        text = stringResource(Res.string.uncommited_changes_secondary_button_abort),
        colors = MaterialTheme.styleColors.destructive,
        modifier = modifier,
        onClick = onClick,
    )
}

/** A status pane button in a style's role: Abort's destructive one, Skip's neutral one (fork-only). */
@Composable
private fun RoleButton(
    text: String,
    colors: RoleColors,
    modifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .then(if (enabled) Modifier.handMouseClickable { onClick() } else Modifier.alpha(DISABLED_BUTTON_ALPHA))
            .focusable(false)
            .clip(MaterialTheme.shapes.small)
            .background(colors.background)
            .roleBorder(colors.border, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.body1.copy(color = colors.content),
        )
    }
}

@Composable
fun ConfirmationButton(
    text: String,
    modifier: Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    onClick: () -> Unit,
) {
    val (backgroundColor, contentColor) = if (enabled) {
        (MaterialTheme.styleColors.mainAction.background to MaterialTheme.styleColors.mainAction.content)
    } else {
        (MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
            .compositeOver(MaterialTheme.colors.surface) to MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.disabled))
    }

    Box(
        modifier = modifier
            .handMouseClickable { if (enabled) onClick() }
            .focusable(false) // TODO this and the abort button should be focusable (show some kind of border when focused?)
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.body1.copy(color = contentColor),
        )
    }
}

/** The header button's label while a search narrows the list: "Stage 3 matches" (fork-only, see [BulkTarget]). */
@Composable
private fun ActionInfo.withMatches(entryType: EntryType, target: BulkTarget): ActionInfo {
    val staged = entryType == EntryType.STAGED

    val title = when (target) {
        BulkTarget.NoMatches -> stringResource(
            if (staged) Res.string.uncommited_changes_staged_no_matches_action
            else Res.string.uncommited_changes_unstaged_no_matches_action
        )

        is BulkTarget.Matches -> if (target.entries.size == 1) {
            stringResource(
                if (staged) Res.string.uncommited_changes_staged_one_match_action
                else Res.string.uncommited_changes_unstaged_one_match_action
            )
        } else {
            stringResource(
                if (staged) Res.string.uncommited_changes_staged_matches_action
                else Res.string.uncommited_changes_unstaged_matches_action,
                target.entries.size,
            )
        }

        else -> return this
    }

    return copy(applyToAllTitle = title)
}

@Composable
fun getActionInfo(entryType: EntryType): ActionInfo {
    val applyToOneTitle: String
    val applyToAllTitle: String
    val applyToSelectedTitle: String
    val icon: DrawableResource
    val colors: RoleColors

    if (entryType == EntryType.STAGED) {
        applyToOneTitle = stringResource(Res.string.uncommited_changes_staged_item_action)
        applyToAllTitle = stringResource(Res.string.uncommited_changes_staged_all_items_action)
        applyToSelectedTitle = stringResource(Res.string.uncommited_changes_staged_selected_items_action)
        icon = Res.drawable.remove_done
        // Fork-only: unstaging loses nothing
        colors = MaterialTheme.styleColors.neutral
    } else {
        applyToOneTitle = stringResource(Res.string.uncommited_changes_unstaged_item_action)
        applyToAllTitle = stringResource(Res.string.uncommited_changes_unstaged_all_items_action)
        applyToSelectedTitle = stringResource(Res.string.uncommited_changes_unstaged_selected_items_action)
        icon = Res.drawable.done
        colors = MaterialTheme.styleColors.action
    }

    return ActionInfo(
        applyToOneTitle = applyToOneTitle,
        applyToAllTitle = applyToAllTitle,
        applyToSelectedTitle = applyToSelectedTitle,
        icon = icon,
        colors = colors,
    )
}


internal suspend fun copyEntriesPath(
    clipboard: Clipboard,
    entries: List<StatusEntry>,
    relative: Boolean,
    repositoryPath: String?,
) {
    val pathsToCopy = entries.joinToString("\n") { entry ->
        if (relative) {
            entry.filePath
        } else {
            // Fork-only: the status state holds the git dir (`<repo>/.git`, or a linked worktree's folder under
            // `<common git dir>/worktrees/`), so the working tree comes from it
            repositoryPath?.let { workTreeOf(it) }.orEmpty() + systemSeparator + entry.filePath
        }
    }

    clipboard.setClipboardText(pathsToCopy)
}

/** A role button that can't be clicked, as Material's disabled buttons look (fork-only). */
private const val DISABLED_BUTTON_ALPHA = 0.38f
