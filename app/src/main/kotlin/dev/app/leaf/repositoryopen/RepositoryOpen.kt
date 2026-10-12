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
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.ui.*
import dev.app.leaf.ui.components.BottomInfoBar
import dev.app.leaf.ui.components.TripleVerticalSplitPanel
import dev.app.leaf.ui.conflicts.ConflictResolver
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.diff.DiffPane
import dev.app.leaf.ui.log.Log
import dev.app.leaf.ui.status.OperationInProgressBanner
import dev.app.leaf.ui.status.StatusPane
import dev.app.leaf.ui.status.abortOperationInProgress
import dev.app.leaf.ui.status.operationInProgress
import dev.app.leaf.updates.Update
import kotlinx.coroutines.flow.collectLatest
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

    val focusRequester = remember { FocusRequester() }
    var showOpenPopup by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
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
                        // The resolver shows in place of file history, so it closes first.
                        val blame = blameState
                        if (showConflictResolver) {
                            repositoryOpenViewModel.closeConflictResolver()
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

                    else -> false
                }

            }
    ) {
        CompositionLocalProvider(
            LocalTabFocusRequester provides focusRequester
        ) {
            Column(modifier = Modifier.weight(1f)) {
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

                // Fork-only: stays while a merge, rebase, cherry-pick or revert is unfinished, whoever started it
                val statusState by repositoryOpenViewModel.statusState.collectAsState()
                val inProgress = operationInProgress(statusState)

                if (inProgress != null) {
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

        Spacer(
            modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colors.primaryVariant.copy(alpha = 0.2f))
        )


        val userInfo by repositoryOpenViewModel.authorInfoSimple.collectAsState()
        val newUpdate = repositoryOpenViewModel.update.collectAsState().value

        RepositoryOpenBottomInfoBar(
            userInfo,
            newUpdate,
            onOpenUrlInBrowser = { repositoryOpenViewModel.openUrlInBrowser(it) },
            onShowAuthorInfoDialog = { onNavigate(Screen.Author) },
        )
    }

    LaunchedEffect(repositoryOpenViewModel) {
        focusRequester.requestFocus()
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

@Composable
private fun RepositoryOpenBottomInfoBar(
    userInfo: UiDataState<Identity>,
    newUpdate: Update?,
    onOpenUrlInBrowser: (String) -> Unit,
    onShowAuthorInfoDialog: () -> Unit,
) {
    BottomInfoBar(
        newUpdate,
        onOpenUrlInBrowser,
        leadingContent = {
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
        }
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

    if (showConflictResolver && conflictResolverViewModel != null) {
        // Fork-only: the built-in conflict resolver takes the whole tab, as file history does
        ConflictResolver(
            viewModel = conflictResolverViewModel,
            onConfirmUseSide = { entry, side, sides, onConfirm ->
                onNavigate(Screen.ConfirmAction(ConfirmableAction.UseConflictSide(listOf(entry), side, sides), onConfirm))
            },
        )
    } else if (showHistory) {
        val historyViewModel = repositoryOpenViewModel.historyViewModel

        if (historyViewModel != null) {
            val tabFocusRequester = LocalTabFocusRequester.current

            FileHistory(
                historyViewModel = historyViewModel,
                onClose = {
                    repositoryOpenViewModel.closeHistory()
                    // Fork-only: file history had the focus, so the tab takes it back, as when the diff closes
                    tabFocusRequester.requestFocus()
                }
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
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    val statusState by viewModel.statusState.collectAsState()

    // We create 2 mutableStates here because using directly the flow makes compose lose some drag events for some reason
    var firstWidth by remember(viewModel) { mutableStateOf(viewModel.firstPaneWidth.value) }
    var thirdWidth by remember(viewModel) { mutableStateOf(viewModel.thirdPaneWidth.value) }

    LaunchedEffect(Unit) {
        // Update the pane widths if they have been changed in a different tab
        viewModel.onPanelsWidthPersisted.collectLatest {
            firstWidth = viewModel.firstPaneWidth.value
            thirdWidth = viewModel.thirdPaneWidth.value
        }
    }

    TripleVerticalSplitPanel(
        modifier = Modifier.fillMaxSize(),
        firstWidth = if (rebaseInteractiveState is RebaseInteractiveState.AwaitingInteraction) 0f else firstWidth,
        thirdWidth = thirdWidth,
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
                        onSelectCommit = { viewModel.selectCommit(it) }
                    )
                } else {
                    Column {
                        Box(modifier = Modifier.weight(1f, true)) {
                            if (diffSelected?.entries?.count() == 1) {
                                val tabFocusRequester = LocalTabFocusRequester.current

                                DiffPane(
                                    viewModel = viewModel,
                                    onCloseDiffView = {
                                        tabFocusRequester.requestFocus()
                                    },
                                    onConfirmAction = { action, onConfirm, onStopAsking ->
                                        onNavigate(Screen.ConfirmAction(action, onConfirm, onStopAsking))
                                    },
                                )
                            } else {
                                Log(
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
                        }

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
                val worktreeReviewState = viewModel.worktreeReviewState.collectAsState().value

                if (worktreeReviewState != null) {
                    WorktreeReview(viewModel = viewModel, reviewState = worktreeReviewState)
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

