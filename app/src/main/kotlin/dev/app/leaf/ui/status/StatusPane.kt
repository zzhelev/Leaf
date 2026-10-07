@file:OptIn(ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package dev.app.leaf.ui.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.systemSeparator
import dev.app.leaf.compose.rememberInTab
import dev.app.leaf.domain.extensions.parentDirectoryPath
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.discardable
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.icon
import dev.app.leaf.extensions.iconColor
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.abortButton
import dev.app.leaf.theme.textFieldColors
import dev.app.leaf.ui.ChangedFilesList
import dev.app.leaf.ui.components.*
import dev.app.leaf.ui.components.sort.FilesSortMenuButton
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.statusDirEntriesContextMenuItems
import dev.app.leaf.ui.context_menu.statusEntriesContextMenuItems
import dev.app.leaf.ui.context_menu.statusEntryContextMenuItems
import dev.app.leaf.ui.dialogs.CommitAuthorDialog
import dev.app.leaf.ui.resizePointerIconNorth
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun StatusPane(
    statusState: StatusState,
    onAction: (StatusAction) -> Unit,
    onBlameFile: (String) -> Unit,
    onHistoryFile: (String) -> Unit,
    /** Asks to discard [entries], the unstaged changes of a folder, keeping its [keptNewFiles] new files. */
    onDiscardFolderChanges: (folderPath: String, entries: List<StatusEntry>, keptNewFiles: Int) -> Unit,
    completedTasks: StateFlow<List<CompletedTask>>,
) {
    val swapUncommittedChanges = statusState.swapUncommittedChanges
    val stagedListState = rememberInTab("statusStagedListState", statusState.staged) {
        LazyListState()
    }
    val unstagedListState = rememberInTab("statusUnstagedListState", statusState.unstaged) {
        LazyListState()
    }

    val commitMessage = statusState.commitMessage

    val isAmend = statusState.isAmend
    val isAmendRebaseInteractive = statusState.isAmendRebaseInteractive
    val committerDataRequestState = statusState.committerDataRequestState
    val rebaseInteractiveState = statusState.rebaseInteractiveState
    val selectedUnstagedDiffEntries = statusState.selectedUnstagedDiffEntries
    val selectedStagedDiffEntries = statusState.selectedStagedDiffEntries

    val showSearchStaged = statusState.showSearchStaged
    val searchFilterStaged = statusState.searchFilterStaged
    val showSearchUnstaged = statusState.showSearchUnstaged
    val searchFilterUnstaged = statusState.searchFilterUnstaged

    val isAmenableRebaseInteractive =
        statusState.repositoryState.isRebasing && rebaseInteractiveState is RebaseInteractiveState.ProcessingCommits && rebaseInteractiveState.isCurrentStepAmenable

    val doCommit = {
        onAction(StatusAction.Commit(commitMessage.text))
        Unit
    }

    val canCommit = commitMessage.text.isNotEmpty() && statusState.hasStagedFiles
    val canAmend = commitMessage.text.isNotEmpty() && statusState.hasPreviousCommits
    val tabFocusRequester = LocalTabFocusRequester.current

    LaunchedEffect(statusState) {
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
                    statusState.hasUnstagedFiles,
                    rebaseInteractiveState,
                    statusState.hasStagedFiles,
                    isAmendRebaseInteractive,
                    !statusState.isLoading && statusState.haveConflictsBeenSolved,
                    setCommitMessage = {
                        onAction(StatusAction.UpdateCommitMessage(it))
                    },
                    onResetRepoState = {
                        onAction(StatusAction.ResetRepositoryState)
                        onAction(StatusAction.UpdateCommitMessage(TextFieldValue("")))
                    },
                    onAbortRebase = {
                        onAction(StatusAction.AbortRebase)
                        onAction(StatusAction.UpdateCommitMessage(TextFieldValue("")))
                    },
                    onAmendChecked = { amend ->
                        if (amend && commitMessage.text.isEmpty()) {
                            onAction(StatusAction.UpdateCommitMessage(TextFieldValue(statusState.previousCommitMessage.orEmpty())))
                        }
                        onAction(StatusAction.ToggleAmend(amend))
                    },
                    onContinueRebase = { onAction(StatusAction.ContinueRebase(commitMessage.text)) },
                    onSkipRebase = { onAction(StatusAction.SkipRebase) },
                    onAmendRebaseInteractiveChecked = { amend ->
                        if (amend && commitMessage.text.isEmpty()) {
                            onAction(StatusAction.UpdateCommitMessage(TextFieldValue(statusState.previousCommitMessage.orEmpty())))
                        }

                        onAction(StatusAction.ToggleAmendRebaseInteractive(amend))
                    },
                    modifier = Modifier.height(heights.commitField.dp),
                )
            }
        }
    }
}

/** The handle between two sections of the pane. Dragging it resizes them, and a double-click resets them. */
@Composable
private fun SectionDivider(
    /** How far the handle moved down, in dp. */
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    val density = LocalDensity.current.density
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(STATUS_SECTION_DIVIDER_HEIGHT.dp)
            .pointerHoverIcon(resizePointerIconNorth)
            .draggable(
                state = rememberDraggableState { onDrag(it / density) },
                orientation = Orientation.Vertical,
                onDragStopped = { onDragStopped() },
            )
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnDoubleClick() })
            }
    )
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
    onDiscardFolderChanges: (folderPath: String, entries: List<StatusEntry>, keptNewFiles: Int) -> Unit,
    onAction: (StatusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val title = when (entryType) {
        EntryType.STAGED -> stringResource(Res.string.uncommited_changes_staged_title)
        EntryType.UNSTAGED -> stringResource(Res.string.uncommited_changes_unstaged_title)
    }

    val actionInfo = getActionInfo(entryType)
    val rows = when (entryType) {
        EntryType.STAGED -> statusState.stagedRows
        EntryType.UNSTAGED -> statusState.unstagedRows
    }

    // Unstaged only, and only for a folder with something to restore
    val folderDiscardAction: (folderPath: String) -> (() -> Unit)? = { folderPath ->
        val shownEntries = statusState.entriesShownUnder(folderPath, entryType)
        val entries = shownEntries.discardable()

        if (entryType == EntryType.UNSTAGED && entries.isNotEmpty()) {
            { onDiscardFolderChanges(folderPath, entries, shownEntries.size - entries.size) }
        } else {
            null
        }
    }

    ChangesList(
        title = title,
        actionInfo = actionInfo,
        entryType = entryType,
        rows = rows,
        folderDiscardAction = folderDiscardAction,
        viewState = statusState.viewState,
        showSearch = showSearch,
        searchFilter = searchFilter,
        listState = listState,
        selectedEntries = selectedEntries,
        onSearchFilterToggled = onSearchFilterToggled,
        onSearchFocused = onSearchFocused,
        onBlameFile = onBlameFile,
        onHistoryFile = onHistoryFile,
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
    entryType: EntryType,
    rows: List<FileRow<StatusEntry>>,
    /** What discarding a folder does, or null when its menu shouldn't offer it. */
    folderDiscardAction: (folderPath: String) -> (() -> Unit)?,
    viewState: FilesViewState,
    showSearch: Boolean,
    searchFilter: TextFieldValue,
    listState: LazyListState,
    selectedEntries: List<DiffType.UncommittedDiff>,
    onSearchFilterToggled: (Boolean) -> Unit,
    onSearchFocused: () -> Unit,
    onBlameFile: (String) -> Unit,
    onHistoryFile: (String) -> Unit,
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
            onReset = { onAction(StatusAction.Reset(statusEntry)) },
            onDelete = { onAction(StatusAction.Delete(statusEntry)) },
            onOpenFileInFolder = { onAction(StatusAction.OpenInFolder(statusEntry.parentDirectoryPath)) },
            onCopyFilePath = { relative ->
                onCopy(
                    relative,
                    listOf(statusEntry),
                )
            },
        )
    }

    fun selectedEntriesContextMenu(): (StatusEntry) -> List<ContextMenuElement> = {
        statusEntriesContextMenuItems(
            selectedEntriesCount = selectedEntries.count(),
            entryType = entryType,
            onDiscard = { onAction(StatusAction.DiscardSelected(entryType)) },
            onStageSelected = { onAction(StatusAction.SelectedEntriesAction(EntryType.UNSTAGED)) },
            onUnstageSelected = { onAction(StatusAction.SelectedEntriesAction(EntryType.STAGED)) },
            onCopyFilesPath = { relative ->
                onCopy(
                    relative,
                    selectedEntries.map { it.statusEntry },
                )
            },
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
            backgroundButton = actionInfo.color,
            modifier = Modifier
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CommitField(
    canCommit: Boolean,
    isAmend: Boolean,
    canAmend: Boolean,
    doCommit: () -> Unit,
    commitMessage: TextFieldValue,
    previousCommitMessage: String?,
    repositoryState: RepositoryState,
    isAmenableRebaseInteractive: Boolean,
    hasUnstagedFiles: Boolean,
    rebaseInteractiveState: RebaseInteractiveState,
    hasStagedFiles: Boolean,
    isAmendRebaseInteractive: Boolean,
    haveConflictsBeenSolved: Boolean,
    setCommitMessage: (TextFieldValue) -> Unit,
    onResetRepoState: () -> Unit,
    onAbortRebase: () -> Unit,
    onContinueRebase: () -> Unit,
    onSkipRebase: () -> Unit,
    onAmendChecked: (Boolean) -> Unit,
    onAmendRebaseInteractiveChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isReadOnlyRebase = repositoryState.isRebasing && !isAmenableRebaseInteractive
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        TextField(
            modifier = Modifier
                .fillMaxWidth()
                .weight(weight = 1f, fill = true)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.matchesBinding(KeybindingOption.TEXT_ACCEPT) && (canCommit || isAmend && canAmend)) {
                        doCommit()
                        true
                    } else
                        false
                },
            value = if (isReadOnlyRebase) TextFieldValue(previousCommitMessage.orEmpty()) else commitMessage,
            onValueChange = setCommitMessage,
            enabled = !repositoryState.isRebasing || isAmenableRebaseInteractive,
            label = {
                val text = if (isReadOnlyRebase) {
                    stringResource(Res.string.uncommited_changes_text_input_label_message_read_only)
                } else {
                    stringResource(Res.string.uncommited_changes_text_input_label_message)
                }

                Text(
                    text = text,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primaryVariant,
                )
            },
            colors = textFieldColors(),
            textStyle = MaterialTheme.typography.body1,
        )

        when {
            repositoryState.isMerging -> MergeButtons(
                haveConflictsBeenSolved = !hasUnstagedFiles,
                onAbort = onResetRepoState,
                onMerge = { doCommit() }
            )

            repositoryState.isRebasing -> {
                val isCurrentStepAmenable =
                    (rebaseInteractiveState as? RebaseInteractiveState.ProcessingCommits)?.isCurrentStepAmenable == true
                RebasingButtons(
                    canContinue = hasStagedFiles || hasUnstagedFiles || (isAmenableRebaseInteractive && isAmendRebaseInteractive && commitMessage.text.isNotEmpty()),
                    haveConflictsBeenSolved = !hasUnstagedFiles,
                    onAbort = onAbortRebase,
                    onContinue = onContinueRebase,
                    onSkip = onSkipRebase,
                    isAmendable = isCurrentStepAmenable,
                    isAmend = isAmendRebaseInteractive,
                    onAmendChecked = onAmendRebaseInteractiveChecked,
                )
            }

            repositoryState.isCherryPicking -> CherryPickingButtons(
                haveConflictsBeenSolved = !hasUnstagedFiles,
                onAbort = onResetRepoState,
                onCommit = {
                    doCommit()
                }
            )

            repositoryState.isReverting -> RevertingButtons(
                haveConflictsBeenSolved = haveConflictsBeenSolved,
                canCommit = commitMessage.text.isNotBlank(),
                onAbort = onResetRepoState,
                onCommit = {
                    doCommit()
                }
            )

            else -> UncommittedChangesButtons(
                canCommit = canCommit,
                canAmend = canAmend,
                isAmend = isAmend,
                onAmendChecked = onAmendChecked,
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
    onAmendChecked: (Boolean) -> Unit,
    onCommit: () -> Unit,
) {
    val buttonText = if (isAmend)
        stringResource(Res.string.uncommited_changes_primary_button_amend)
    else
        stringResource(Res.string.uncommited_changes_primary_button_commit)

    Column {
        CheckboxText(
            value = isAmend,
            onCheckedChange = { onAmendChecked(!isAmend) },
            text = stringResource(Res.string.uncommited_changes_amend_check)
        )
        Row(
            modifier = Modifier
                .padding(top = 2.dp)
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
    haveConflictsBeenSolved: Boolean,
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
            enabled = haveConflictsBeenSolved,
            onClick = onMerge,
        )
    }
}

@Composable
fun CherryPickingButtons(
    haveConflictsBeenSolved: Boolean,
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
            enabled = haveConflictsBeenSolved,
            onClick = onCommit,
        )
    }
}

@Composable
fun RebasingButtons(
    canContinue: Boolean,
    isAmendable: Boolean,
    isAmend: Boolean,
    onAmendChecked: (Boolean) -> Unit,
    haveConflictsBeenSolved: Boolean,
    onAbort: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    Column {
        if (isAmendable) {
            CheckboxText(
                value = isAmend,
                onCheckedChange = { onAmendChecked(!isAmend) },
                text = stringResource(Res.string.uncommited_changes_amend_check)
            )
        }

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

            if (canContinue) {
                ConfirmationButton(
                    text = stringResource(Res.string.uncommited_changes_primary_button_continue),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                        .fillMaxHeight(),
                    enabled = haveConflictsBeenSolved,
                    onClick = onContinue,
                )
            } else {
                ConfirmationButton(
                    text = stringResource(Res.string.uncommited_changes_primary_button_skip),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                        .fillMaxHeight(),
                    onClick = onSkip,
                )
            }
        }
    }
}

@Composable
fun RevertingButtons(
    canCommit: Boolean,
    haveConflictsBeenSolved: Boolean,
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
            enabled = canCommit && haveConflictsBeenSolved,
            onClick = onCommit,
        )
    }
}

@Composable
fun AbortButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .handMouseClickable { onClick() }
            .focusable(false)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colors.abortButton),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.uncommited_changes_secondary_button_abort),
            style = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onError),
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
        (MaterialTheme.colors.primary to MaterialTheme.colors.onPrimary)
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

@Composable
fun getActionInfo(entryType: EntryType): ActionInfo {
    val applyToOneTitle: String
    val applyToAllTitle: String
    val applyToSelectedTitle: String
    val icon: DrawableResource
    val color: Color
    val textColor: Color

    if (entryType == EntryType.STAGED) {
        applyToOneTitle = stringResource(Res.string.uncommited_changes_staged_item_action)
        applyToAllTitle = stringResource(Res.string.uncommited_changes_staged_all_items_action)
        applyToSelectedTitle = stringResource(Res.string.uncommited_changes_staged_selected_items_action)
        icon = Res.drawable.remove_done
        color = MaterialTheme.colors.error
        textColor = MaterialTheme.colors.onError
    } else {
        applyToOneTitle = stringResource(Res.string.uncommited_changes_unstaged_item_action)
        applyToAllTitle = stringResource(Res.string.uncommited_changes_unstaged_all_items_action)
        applyToSelectedTitle = stringResource(Res.string.uncommited_changes_unstaged_selected_items_action)
        icon = Res.drawable.done
        color = MaterialTheme.colors.primary
        textColor = MaterialTheme.colors.onPrimary
    }

    return ActionInfo(
        applyToOneTitle = applyToOneTitle,
        applyToAllTitle = applyToAllTitle,
        applyToSelectedTitle = applyToSelectedTitle,
        icon = icon,
        color = color,
        textColor = textColor,
    )
}


private suspend fun copyEntriesPath(
    clipboard: Clipboard,
    entries: List<StatusEntry>,
    relative: Boolean,
    repositoryPath: String?,
) {
    val pathsToCopy = entries.joinToString("\n") { entry ->
        if (relative) {
            entry.filePath
        } else {
            repositoryPath.orEmpty() + systemSeparator + entry.filePath
        }
    }

    clipboard.setClipboardText(pathsToCopy)
}