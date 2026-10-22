package dev.app.leaf.repositoryopen

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTab
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.Screen
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.bottom_info_bar_email_not_set
import dev.app.leaf.app.generated.resources.bottom_info_bar_name_and_email
import dev.app.leaf.app.generated.resources.bottom_info_bar_name_not_set
import dev.app.leaf.domain.models.CenterSplitSizes
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.ui.*
import dev.app.leaf.ui.compare.ComparisonFullView
import dev.app.leaf.ui.compare.ComparisonPane
import dev.app.leaf.ui.components.BottomInfoBar
import dev.app.leaf.ui.components.BranchesPaneToggle
import dev.app.leaf.ui.components.ChangesPaneToggle
import dev.app.leaf.ui.ChangesPaneState
import dev.app.leaf.ui.components.TripleVerticalSplitPanel
import dev.app.leaf.ui.conflicts.ConflictResolver
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.diff.DiffLogToggle
import dev.app.leaf.ui.diff.DiffPane
import dev.app.leaf.ui.diff.FollowedDiffPlaceholder
import dev.app.leaf.ui.log.Log
import dev.app.leaf.ui.log.LocalLogFocusRequester
import dev.app.leaf.ui.status.OperationInProgressBanner
import dev.app.leaf.ui.status.UnsupportedIndexBanner
import dev.app.leaf.ui.LocalAppChrome
import dev.app.leaf.ui.status.StatusPane
import dev.app.leaf.ui.status.abortOperationInProgress
import dev.app.leaf.ui.status.operationInProgress
import dev.app.leaf.domain.refresh.WatchUsage
import dev.app.leaf.updates.Update
import dev.app.leaf.viewmodels.ConflictResolverViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun RepositoryOpenPage(
    repositoryOpenViewModel: RepositoryOpenViewModel,
    onNavigate: (Screen) -> Unit, // TODO Perhaps have specific callbacks instead of directly navigating
) {
    val repositoryState by repositoryOpenViewModel.repositoryState.collectAsState()
    val selectedItem by repositoryOpenViewModel.selectedItem.collectAsState()
    val blameState by repositoryOpenViewModel.blameState.collectAsState()
    val showHistory by repositoryOpenViewModel.showHistory.collectAsState()
    val showConflictResolver by repositoryOpenViewModel.showConflictResolver.collectAsState()
    val tab = LocalTab.current

    // Fork-only: the conflict resolver's Focus hides what is around it, the tab bar included
    val resolverFocused = showConflictResolver && repositoryOpenViewModel.conflictResolverViewModel.isFocused()
    val chrome = LocalAppChrome.current

    DisposableEffect(chrome, resolverFocused) {
        chrome.tabBarHidden = resolverFocused
        onDispose { chrome.tabBarHidden = false }
    }

    val focusRequester = remember { FocusRequester() }
    var showOpenPopup by remember { mutableStateOf(false) }

    // Fork-only: whether the tab, or anything in it, has the focus
    var tabHasFocus by remember { mutableStateOf(false) }
    val isBranchesPaneFolded by repositoryOpenViewModel.branchesPaneFolded.collectAsState()
    val changesPane by repositoryOpenViewModel.changesPane.collectAsState()
    val isChangesPaneShown = changesPane.isShown

    // Fork-only: folding the branches pane or the changes pane removes whatever in it had the focus, and the tab's
    // shortcuts need something in the tab to have it. So one frame after a fold, when the focus is nowhere in the tab, the
    // tab takes it.
    LaunchedEffect(isBranchesPaneFolded, isChangesPaneShown) {
        if (isBranchesPaneFolded || !isChangesPaneShown) {
            withFrameNanos { }

            if (!tabHasFocus) {
                try {
                    focusRequester.requestFocus()
                } catch (_: IllegalStateException) {
                    // The tab is gone
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { tabHasFocus = it.hasFocus }
            .focusable(true)
            .onPreviewKeyEvent {
                when {
                    it.matchesBinding(KeybindingOption.PULL) -> {
                        repositoryOpenViewModel.pull(PullType.DEFAULT)
                        true
                    }

                    it.matchesBinding(KeybindingOption.PUSH) -> {
                        repositoryOpenViewModel.push(force = false, pushTags = false)
                        true
                    }

                    it.matchesBinding(KeybindingOption.BRANCH_CREATE) -> {
                        onNavigate(Screen.BranchCreate(null))
                        true
                    }

                    it.matchesBinding(KeybindingOption.STASH) -> {
                        // Fork-only: Save in the conflict resolver, as git can't stash while files have conflicts
                        if (showConflictResolver) {
                            repositoryOpenViewModel.saveInConflictResolver()
                        } else {
                            repositoryOpenViewModel.stash()
                        }
                        true
                    }

                    it.matchesBinding(KeybindingOption.STASH_POP) -> {
                        repositoryOpenViewModel.popStash()
                        true
                    }

                    it.matchesBinding(KeybindingOption.EXIT) -> {
                        // Fork-only: this handler sees Esc before file history and blame, whose own handlers so never
                        // ran. The tab takes the focus back, as when the diff closes, so that the next key reaches it.
                        // The resolver shows in place of file history, so it goes first, and leaves its Focus first.
                        val blame = blameState
                        if (showConflictResolver) {
                            repositoryOpenViewModel.escapeConflictResolver()
                        } else if (showHistory) {
                            repositoryOpenViewModel.closeHistory()
                            focusRequester.requestFocus()
                        } else if (blame is BlameState.Loaded && !blame.isMinimized) {
                            repositoryOpenViewModel.resetBlameState()
                            focusRequester.requestFocus()
                        } else {
                            repositoryOpenViewModel.closeLastView()
                        }
                        true
                    }

                    it.matchesBinding(KeybindingOption.REFRESH) -> {
                        repositoryOpenViewModel.refreshAll()
                        true
                    }

                    it.matchesBinding(KeybindingOption.OPEN_REPOSITORY) -> {
                        showOpenPopup = true
                        true
                    }

                    it.matchesBinding(KeybindingOption.SETTINGS) -> {
                        onNavigate(Screen.Settings)
                        true
                    }

                    // Fork-only: folds the branches pane away, or shows it again
                    it.matchesBinding(KeybindingOption.BRANCHES_PANE) -> {
                        repositoryOpenViewModel.toggleBranchesPane()
                        true
                    }

                    // Fork-only: folds the changes pane away, or shows it again
                    it.matchesBinding(KeybindingOption.CHANGES_PANE) -> {
                        repositoryOpenViewModel.toggleChangesPane()
                        true
                    }

                    else -> false
                }

            }
    ) {
        CompositionLocalProvider(
            LocalTabFocusRequester provides focusRequester
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!resolverFocused) {
                    Menu(
                        viewModel = repositoryOpenViewModel,
                        modifier = Modifier
                            .padding(
                                vertical = 4.dp
                            )
                            .fillMaxWidth(),
                        onCreateBranch = { onNavigate(Screen.BranchCreate(null)) },
                        onNewWorktree = { onNavigate(Screen.WorktreeCreate(NewWorktreeStart.Base)) },
                        onStashWithMessage = { onNavigate(Screen.StashWithMessage) },
                        onOpenAnotherRepository = { repositoryOpenViewModel.openAnotherRepository(it, tab) },
                        onOpenAnotherRepositoryFromPicker = {
                            val repoToOpen = repositoryOpenViewModel.openDirectoryPicker()

                            if (repoToOpen != null) {
                                repositoryOpenViewModel.openAnotherRepository(repoToOpen, tab)
                            }
                        },
                        onQuickActions = { onNavigate(Screen.QuickActions) },
                        onShowSettingsDialog = { onNavigate(Screen.Settings) },
                        onForcePush = {
                            val branchName = repositoryOpenViewModel.branchesState.value.currentBranch?.simpleName.orEmpty()
                            val action = ConfirmableAction.ForcePush(branchName)

                            onNavigate(
                                Screen.ConfirmAction(
                                    action = action,
                                    onConfirm = { repositoryOpenViewModel.push(force = true, pushTags = false) },
                                )
                            )
                        },
                        showOpenPopup = showOpenPopup,
                        onShowOpenPopupChange = { showOpenPopup = it }
                    )
                }

                // Fork-only: stays while a merge, rebase, cherry-pick or revert is unfinished, whoever started it
                val statusState by repositoryOpenViewModel.statusState.collectAsState()
                val inProgress = operationInProgress(statusState)

                if (inProgress != null && !resolverFocused) {
                    OperationInProgressBanner(
                        inProgress = inProgress,
                        onResolveConflicts = { repositoryOpenViewModel.resolveConflicts() },
                        onAbort = {
                            abortOperationInProgress(
                                statusState,
                                onAction = { repositoryOpenViewModel.onAction(it) },
                                onConfirmAction = { action, onConfirm ->
                                    onNavigate(Screen.ConfirmAction(action, onConfirm))
                                },
                            )
                        },
                    )
                }

                // Fork-only: stays while JGit can't read the worktree's index, whoever wrote or converts it
                val unsupportedIndex by repositoryOpenViewModel.unsupportedIndex.collectAsState()

                unsupportedIndex?.let { index ->
                    if (!resolverFocused) {
                        UnsupportedIndexBanner(
                            index = index,
                            onConvert = {
                                onNavigate(
                                    Screen.ConvertIndex(index, onConvert = { repositoryOpenViewModel.convertIndex(index) })
                                )
                            },
                        )
                    }
                }

                RepoContent(
                    repositoryOpenViewModel = repositoryOpenViewModel,
                    selectedItem = selectedItem,
                    repositoryState = repositoryState,
                    blameState = blameState,
                    showHistory = showHistory,
                    showConflictResolver = showConflictResolver,
                    onNavigate = onNavigate,
                )
            }
        }

        if (!resolverFocused) {
            Spacer(
                modifier = Modifier
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colors.primaryVariant.copy(alpha = 0.2f))
            )


            val userInfo by repositoryOpenViewModel.authorInfoSimple.collectAsState()
            val newUpdate = repositoryOpenViewModel.update.collectAsState().value
            val watchWarning = repositoryOpenViewModel.watchWarning.collectAsState().value

            RepositoryOpenBottomInfoBar(
                userInfo,
                newUpdate,
                watchWarning,
                isBranchesPaneFolded = isBranchesPaneFolded,
                onToggleBranchesPane = { repositoryOpenViewModel.toggleBranchesPane() },
                changesPane = changesPane,
                onToggleChangesPane = { repositoryOpenViewModel.toggleChangesPane() },
                onOpenUrlInBrowser = { repositoryOpenViewModel.openUrlInBrowser(it) },
                onShowAuthorInfoDialog = { onNavigate(Screen.Author) },
            )
        }
    }

    LaunchedEffect(repositoryOpenViewModel) {
        focusRequester.requestFocus()
    }

    // Fork-only: the conflict resolver has the focus while it shows, so the tab takes it back when it closes, however it
    // closed (Close, Esc, nothing left to resolve, a switch to another worktree)
    LaunchedEffect(repositoryOpenViewModel) {
        repositoryOpenViewModel.showConflictResolver.drop(1).filter { !it }.collect {
            focusRequester.requestFocus()
        }
    }

    // Fork-only: so does a comparison's full view, which closes with Esc, its buttons, or the comparison's end
    LaunchedEffect(repositoryOpenViewModel) {
        repositoryOpenViewModel.comparisonState
            .map { it?.isFullView == true }
            .distinctUntilChanged()
            .drop(1)
            .filter { !it }
            .collect { focusRequester.requestFocus() }
    }

    LaunchedEffect(repositoryOpenViewModel) {
        repositoryOpenViewModel.fastForwardOffers.collect { offer ->
            onNavigate(
                Screen.FastForwardOnCheckout(offer) { fastForward ->
                    repositoryOpenViewModel.checkoutRemoteBranch(offer.remoteBranch, fastForward)
                }
            )
        }
    }
}

/** Whether the conflict resolver is in Focus (fork-only). */
@Composable
private fun ConflictResolverViewModel?.isFocused(): Boolean = this?.state?.collectAsState()?.value?.focused == true

@Composable
private fun RepositoryOpenBottomInfoBar(
    userInfo: UiDataState<Identity>,
    newUpdate: Update?,
    watchWarning: WatchUsage?,
    isBranchesPaneFolded: Boolean,
    onToggleBranchesPane: () -> Unit,
    changesPane: ChangesPaneState,
    onToggleChangesPane: () -> Unit,
    onOpenUrlInBrowser: (String) -> Unit,
    onShowAuthorInfoDialog: () -> Unit,
) {
    BottomInfoBar(
        newUpdate,
        onOpenUrlInBrowser,
        watchWarning = watchWarning,
        leadingContent = {
            // Fork-only: under the branches pane's column, so that it stays in place when the pane folds
            BranchesPaneToggle(isFolded = isBranchesPaneFolded, onToggle = onToggleBranchesPane)

            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .width(1.dp)
                    .height(16.dp)
                    .background(MaterialTheme.colors.onSurface.copy(alpha = 0.18f)),
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .handMouseClickable { onShowAuthorInfoDialog() },
                contentAlignment = Alignment.Center,
            ) {
                val identity = userInfo.data

                if (identity != null) {
                    val name = identity.name ?: stringResource(Res.string.bottom_info_bar_name_not_set)
                    val email = identity.email ?: stringResource(Res.string.bottom_info_bar_email_not_set)

                    Text(
                        text = stringResource(Res.string.bottom_info_bar_name_and_email, name, email),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                    )
                }
            }
        },
        trailingContent = {
            // Fork-only: under the changes pane's column, as the branches pane's is under its own
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .width(1.dp)
                    .height(16.dp)
                    .background(MaterialTheme.colors.onSurface.copy(alpha = 0.18f)),
            )

            ChangesPaneToggle(state = changesPane, onToggle = onToggleChangesPane)
        },
    )
}

@Composable
fun RepoContent(
    repositoryOpenViewModel: RepositoryOpenViewModel,
    selectedItem: SelectedItem,
    repositoryState: RepositoryState,
    blameState: BlameState,
    showHistory: Boolean,
    showConflictResolver: Boolean,
    onNavigate: (Screen) -> Unit,
) {
    val conflictResolverViewModel = repositoryOpenViewModel.conflictResolverViewModel
    val comparisonState by repositoryOpenViewModel.comparisonState.collectAsState()
    val comparisonFullView = comparisonState?.takeIf { it.isFullView }

    if (showConflictResolver && conflictResolverViewModel != null) {
        // Fork-only: the built-in conflict resolver takes the whole tab, as file history does
        ConflictResolver(viewModel = conflictResolverViewModel)
    } else if (comparisonFullView != null) {
        // Fork-only: so does a comparison's full view (docs/fork/compare.md)
        ComparisonFullView(
            viewModel = repositoryOpenViewModel,
            state = comparisonFullView,
            onConfirmAction = { action, onConfirm, onStopAsking ->
                onNavigate(Screen.ConfirmAction(action, onConfirm, onStopAsking))
            },
        )
    } else if (showHistory) {
        val historyViewModel = repositoryOpenViewModel.historyViewModel

        if (historyViewModel != null) {
            val tabFocusRequester = LocalTabFocusRequester.current
            val scope = rememberCoroutineScope()
            val closeHistory: () -> Unit = {
                repositoryOpenViewModel.closeHistory()
                // Fork-only: file history had the focus, so the tab takes it back, as when the diff closes
                tabFocusRequester.requestFocus()
            }

            FileHistory(
                historyViewModel = historyViewModel,
                onClose = closeHistory,
                // Fork-only: looks at the file first, then asks before it overwrites it
                onRestoreVersion = { commit ->
                    scope.launch {
                        historyViewModel.planRestore(commit)?.let { plan ->
                            onNavigate(
                                Screen.ConfirmAction(
                                    action = ConfirmableAction.RestoreFileVersion(historyViewModel.filePath, commit, plan),
                                    onConfirm = { historyViewModel.restore(commit) },
                                )
                            )
                        }
                    }
                },
                onShowChanges = {
                    closeHistory()
                    repositoryOpenViewModel.selectUncommittedChanges()
                },
            )
        }
    } else {
        MainContentView(
            viewModel = repositoryOpenViewModel,
            selectedItem = selectedItem,
            repositoryState = repositoryState,
            blameState = blameState,
            onNavigate = onNavigate,
        )
    }
}

@Composable
fun MainContentView(
    viewModel: RepositoryOpenViewModel,
    selectedItem: SelectedItem,
    repositoryState: RepositoryState,
    blameState: BlameState,
    onNavigate: (Screen) -> Unit,
) {
    val diffSelected by viewModel.diffSelected.collectAsState()
    val rebaseInteractiveState by viewModel.rebaseInteractiveState.collectAsState()
    // Fork-only: the pane's width is kept while it is folded, and comes back when it opens
    val branchesPaneFolded by viewModel.branchesPaneFolded.collectAsState()
    // Fork-only: its width is kept too, and the pane shows anyway while something needs it
    val changesPane by viewModel.changesPane.collectAsState()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    val statusState by viewModel.statusState.collectAsState()

    // We create 2 mutableStates here because using directly the flow makes compose lose some drag events for some reason
    var firstWidth by remember(viewModel) { mutableStateOf(viewModel.firstPaneWidth.value) }
    var thirdWidth by remember(viewModel) { mutableStateOf(viewModel.thirdPaneWidth.value) }

    // Fork-only: a local copy follows the divider between the log and the diff while it is dragged, and is saved when it ends
    var splitSizes by remember(viewModel) { mutableStateOf(viewModel.centerSplitSizes.value) }

    LaunchedEffect(viewModel) {
        viewModel.centerSplitSizes.collect { splitSizes = it }
    }

    LaunchedEffect(Unit) {
        // Update the pane widths if they have been changed in a different tab
        viewModel.onPanelsWidthPersisted.collectLatest {
            firstWidth = viewModel.firstPaneWidth.value
            thirdWidth = viewModel.thirdPaneWidth.value
        }
    }

    TripleVerticalSplitPanel(
        modifier = Modifier.fillMaxSize(),
        firstWidth = if (rebaseInteractiveState is RebaseInteractiveState.AwaitingInteraction || branchesPaneFolded) 0f else firstWidth,
        thirdWidth = if (changesPane.isShown) thirdWidth else 0f,
        first = {
            SidePanel(
                viewModel = viewModel,
                onNavigate = onNavigate,
            )
        },
        second = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                if (
                    rebaseInteractiveState is RebaseInteractiveState.AwaitingInteraction
                    && diffSelected == null
                ) {
                    RebaseInteractive(viewModel, rebaseInteractiveState)
                } else if (blameState is BlameState.Loaded && !blameState.isMinimized) {
                    val tabFocusRequester = LocalTabFocusRequester.current

                    Blame(
                        filePath = blameState.filePath,
                        blameResult = blameState.blameResult,
                        onClose = {
                            viewModel.resetBlameState()
                            // Fork-only: blame had the focus, so the tab takes it back, as when the diff closes
                            tabFocusRequester.requestFocus()
                        },
                        onSelectCommit = { viewModel.selectCommit(it, isStash = false) }
                    )
                } else {
                    Column {
                        val showDiff = diffSelected?.entries?.count() == 1
                        val tabFocusRequester = LocalTabFocusRequester.current
                        val logFocusRequester = remember { FocusRequester() }
                        val keepsLog by viewModel.diffKeepsLog.collectAsState()
                        val missingFile by viewModel.missingFollowedFile.collectAsState()

                        // Fork-only: a closed diff gives the focus to the log, which stays visible, so that its keys go on working
                        val focusAfterClosingDiff: () -> Unit = {
                            try {
                                logFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                                tabFocusRequester.requestFocus()
                            }
                        }
                        val logToggle: @Composable () -> Unit = {
                            DiffLogToggle(keepsLog = keepsLog, onKeepsLogChanged = { viewModel.changeDiffKeepsLog(it) })
                        }

                        // Fork-only: the log stays above the diff (upstream issues 343, 136 and 128). The log is composed
                        // under the diff as it was, where there is no room for both.
                        LogAndDiffSplit(
                            modifier = Modifier.weight(1f, true),
                            sizes = splitSizes,
                            // The interactive rebase's list isn't the log: a diff still covers it, as it always did
                            keepsLog = keepsLog && rebaseInteractiveState !is RebaseInteractiveState.AwaitingInteraction,
                            isDiffShown = showDiff || missingFile != null,
                            onSizesChange = { splitSizes = it },
                            onSizesChangeFinished = { viewModel.saveCenterSplitSizes(splitSizes) },
                            onResetSizes = {
                                splitSizes = CenterSplitSizes()
                                viewModel.saveCenterSplitSizes(splitSizes)
                            },
                            log = { logModifier ->
                                CompositionLocalProvider(LocalLogFocusRequester provides logFocusRequester) {
                                    Log(
                                        modifier = logModifier,
                                        viewModel = viewModel,
                                        selectedItem = selectedItem,
                                        repositoryState = repositoryState,
                                        // TODO Move nav outside of this? Applies to next lines
                                        onCreateBranch = { onNavigate(Screen.BranchCreate(it)) },
                                        onResetBranch = { onNavigate(Screen.BranchReset(it)) },
                                        onCreateTag = { onNavigate(Screen.TagCreate(it)) },
                                        onChangeUpstreamBranch = { onNavigate(Screen.BranchChangeUpstream(it)) },
                                        onRenameBranch = { onNavigate(Screen.BranchRename(it)) },
                                        onDeleteBranch = { onNavigate(Screen.BranchDelete(it)) },
                                        onDeleteTag = { onNavigate(Screen.TagDelete(it)) },
                                        onConfirmAction = { action, onConfirm ->
                                            onNavigate(Screen.ConfirmAction(action, onConfirm))
                                        },
                                        onNewWorktree = { onNavigate(Screen.WorktreeCreate(it)) },
                                    )
                                }
                            },
                            diff = {
                                if (showDiff) {
                                    DiffPane(
                                        viewModel = viewModel,
                                        onCloseDiffView = { focusAfterClosingDiff() },
                                        onConfirmAction = { action, onConfirm, onStopAsking ->
                                            onNavigate(Screen.ConfirmAction(action, onConfirm, onStopAsking))
                                        },
                                        logToggle = logToggle,
                                    )
                                } else {
                                    missingFile?.let { missing ->
                                        FollowedDiffPlaceholder(
                                            missing = missing,
                                            closeRequests = remember(viewModel) {
                                                viewModel.closeViewFlow.filter { it == CloseableView.DIFF }.map { }
                                            },
                                            onShown = { viewModel.addToCloseables() },
                                            onClose = {
                                                viewModel.forgetFollowedFile()
                                                focusAfterClosingDiff()
                                            },
                                            logToggle = logToggle,
                                        )
                                    }
                                }
                            },
                        )

                        if (blameState is BlameState.Loaded) { // BlameState.isMinimized is true here
                            MinimizedBlame(
                                filePath = blameState.filePath,
                                onExpand = { viewModel.expandBlame() },
                                onClose = { viewModel.resetBlameState() }
                            )
                        }
                    }
                }
            }
        },
        third = {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
            ) {
                val commitChangesState = viewModel.commitChangesState.collectAsState().value
                val comparisonState = viewModel.comparisonState.collectAsState().value

                if (comparisonState != null) {
                    ComparisonPane(viewModel = viewModel, state = comparisonState)
                } else if (commitChangesState != null) {
                    CommitChanges(
                        viewModel = viewModel,
                        onBlame = { viewModel.blameFile(it) },
                        onHistory = { viewModel.fileHistory(it) },
                        commitChangesState = commitChangesState,
                    )
                } else if (rebaseInteractiveState !is RebaseInteractiveState.AwaitingInteraction) {
                    StatusPane(
                        statusState = statusState,
                        completedTasks = viewModel.completedTasks,
                        onAction = { viewModel.onAction(it) },
                        onBlameFile = { viewModel.blameFile(it) },
                        onHistoryFile = { viewModel.fileHistory(it) },
                        onDiscardFolderChanges = { folderPath, discard ->
                            onNavigate(Screen.DiscardFolderChanges(folderPath, discard))
                        },
                        onIgnore = { request -> onNavigate(Screen.Ignore(request)) },
                        onConfirmAction = { action, onConfirm -> onNavigate(Screen.ConfirmAction(action, onConfirm)) },
                    )
                }
            }
        },
        onFirstSizeDragStarted = { currentWidth ->
            firstWidth = currentWidth
            viewModel.setFirstPaneWidth(currentWidth)
        },
        onFirstSizeChange = {
            val newWidth = firstWidth + it / density

            if (newWidth > 150 && rebaseInteractiveState !is RebaseInteractiveState.AwaitingInteraction) {
                firstWidth = newWidth
                viewModel.setFirstPaneWidth(newWidth)
            }
        },
        onFirstSizeDragStopped = {
            scope.launch {
                viewModel.persistFirstPaneWidth()
            }
        },
        onThirdSizeChange = {
            val newWidth = thirdWidth - it / density

            if (newWidth > 150) {
                thirdWidth = newWidth
                viewModel.setThirdPaneWidth(newWidth)
            }
        },
        onThirdSizeDragStarted = { currentWidth ->
            thirdWidth = currentWidth
            viewModel.setThirdPaneWidth(currentWidth)
        },
        onThirdSizeDragStopped = {
            scope.launch {
                viewModel.persistThirdPaneWidth()
            }
        },
    )
}

