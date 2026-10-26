@file:OptIn(ExperimentalComposeUiApi::class)

package dev.app.leaf.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.Screen
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.branches.BranchClick
import dev.app.leaf.domain.branches.BranchSelection
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.branches.branchClickOf
import dev.app.leaf.domain.branches.branchesInFolder
import dev.app.leaf.domain.branches.remoteOfSelectionGroup
import dev.app.leaf.domain.branches.selectionGroupOf
import dev.app.leaf.domain.extensions.isValid
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.models.Submodule
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.refresh.WorktreesVisibility
import dev.app.leaf.domain.sorting.RefPanelSettings
import dev.app.leaf.domain.sorting.RefRow
import dev.app.leaf.domain.sorting.RefSection
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.components.FolderEntryContent
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.SideMenuHeader
import dev.app.leaf.ui.components.SideMenuSubentry
import dev.app.leaf.ui.components.sort.RefSortMenuButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.domain.cherrypick.BranchCherryPickItem
import dev.app.leaf.ui.context_menu.*
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.viewmodels.sidepanel.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.eclipse.jgit.submodule.SubmoduleStatus
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.StringSelection

@Composable
fun SidePanel(
    viewModel: RepositoryOpenViewModel,
    onNavigate: (Screen) -> Unit,
) {
    val filter by viewModel.filter.collectAsState()
    val selectedItem by viewModel.selectedItem.collectAsState()

    val branchesState by viewModel.branchesState.collectAsState()
    val remotesState by viewModel.remoteState.collectAsState()
    val tagsState by viewModel.tagsState.collectAsState()
    val stashesState by viewModel.stashesState.collectAsState()
    val submodulesState by viewModel.submodulesState.collectAsState()
    val worktreesState by viewModel.worktreesState.collectAsState()
    val branchWorktrees by viewModel.branchWorktrees.collectAsState()
    val refPanelSettings by viewModel.refPanelSettings.collectAsState()
    val branchesAhead by viewModel.branchesAhead.collectAsState()
    val branchSelection by viewModel.branchSelection.collectAsState()
    // Fork-only: the branch menus' cherry-pick item
    val cherryPick = BranchCherryPick(branchesAhead, branchesState.currentBranch) { branch, item ->
        viewModel.cherryPickFromBranch(branch, item) { onNavigate(Screen.CherryPickBranch(it)) }
    }
    // Fork-only: the local branches' "Squash branch into one commit…" and "Interactive rebase…"
    val commitRewrite = rememberCommitRewrite(
        viewModel = viewModel,
        currentBranch = branchesState.currentBranch,
        onRewrite = { request -> onNavigate(rewriteCommitsScreen(viewModel, request)) },
    )

    val searchFocusRequester = remember { FocusRequester() }
    val tabFocusRequester = LocalTabFocusRequester.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val onConfirmAction: (ConfirmableAction, () -> Unit) -> Unit = { action, onConfirm ->
        onNavigate(Screen.ConfirmAction(action, onConfirm))
    }

    LaunchedEffect(viewModel) {
        viewModel.freeSearchFocusFlow.collectLatest {
            tabFocusRequester.requestFocus()
        }
    }

    // The worktree list refreshes on an interval while it's on screen
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    val isWindowMinimized = LocalWindowMinimized.current

    LaunchedEffect(viewModel, worktreesState.isExpanded, isWindowFocused, isWindowMinimized) {
        viewModel.onWorktreesVisibilityChanged(
            WorktreesVisibility(
                isShown = worktreesState.isExpanded && !isWindowMinimized,
                isWindowFocused = isWindowFocused,
            )
        )
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.onWorktreesVisibilityChanged(WorktreesVisibility.Hidden) }
    }

    Column {
        FilterTextField(
            value = filter,
            onValueChange = { newValue ->
                viewModel.newFilter(newValue)
            },
            modifier = Modifier
                .padding(start = 8.dp)
                .focusRequester(searchFocusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        viewModel.addSidePanelSearchToCloseables()
                    } else {
                        viewModel.removeSidePanelSearchFromCloseables()
                    }
                }
        )

        ScrollableLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp)
        ) {
            localBranches(
                branchesState = branchesState,
                branchWorktrees = branchWorktrees,
                refPanelSettings = refPanelSettings,
                selectedItem = selectedItem,
                viewModel = viewModel,
                onChangeDefaultUpstreamBranch = { onNavigate(Screen.BranchChangeUpstream(it)) },
                onRenameBranch = { onNavigate(Screen.BranchRename(it)) },
                onDeleteBranch = { onNavigate(Screen.BranchDelete(it)) },
                onNewWorktree = { onNavigate(Screen.WorktreeCreate(it)) },
                cherryPick = cherryPick,
                commitRewrite = commitRewrite,
                branchSelection = branchSelection,
                onDeleteBranches = { onNavigate(Screen.BranchesDelete(it)) },
            )

            worktrees(
                worktreesState = worktreesState,
                onExpand = { viewModel.onExpandWorktrees() },
                onWorktreeClicked = { viewModel.selectWorktree(it.worktree) },
                onSwitchToWorktree = { viewModel.switchToWorktree(it.worktree) },
                onOpenInNewTab = { viewModel.openWorktreeInNewTab(it.worktree) },
                onCopyPath = { scope.launch { clipboard.setClipboardText(it.worktree.path) } },
                onChooseBase = { viewModel.chooseWorktreesBase(it) },
                actions = WorktreeActions(
                    onLock = { onNavigate(Screen.WorktreeLock(it)) },
                    onUnlock = { viewModel.unlockWorktree(it) },
                    onRemove = { onNavigate(Screen.WorktreeRemove(it, discard = false)) },
                    onDiscard = { onNavigate(Screen.WorktreeRemove(it, discard = true)) },
                    onPrune = { onNavigate(Screen.WorktreesPrune) },
                    onCleanUp = { onNavigate(Screen.WorktreesCleanUp) },
                    onNew = { onNavigate(Screen.WorktreeCreate(NewWorktreeStart.Base)) },
                    onReview = { viewModel.reviewWorktree(it) },
                    onMerge = { worktree, kind ->
                        worktreesState.mergeTarget?.let { target ->
                            onNavigate(Screen.WorktreeMergeIntoBase(worktree, kind, target))
                        }
                    },
                    onRebase = { worktree ->
                        worktreesState.baseBranch?.let { base ->
                            onNavigate(Screen.WorktreeRebaseOntoBase(worktree, base))
                        }
                    },
                    squashBranch = { branch -> commitRewrite?.branchItem(branch) },
                    editBranch = { branch -> commitRewrite?.branchEditorItem(branch) },
                ),
            )

            remotes(
                remotesState = remotesState,
                worktreesBase = branchWorktrees.baseChoice,
                refPanelSettings = refPanelSettings,
                viewModel = viewModel,
                onShowAddEditRemoteDialog = { onNavigate(Screen.AddEditRemote(it)) },
                onConfirmAction = onConfirmAction,
                onNewWorktree = { onNavigate(Screen.WorktreeCreate(it)) },
                cherryPick = cherryPick,
                selectedItem = selectedItem,
                branchSelection = branchSelection,
                onDeleteBranches = { onNavigate(Screen.BranchesDelete(it)) },
            )

            tags(
                tagsState = tagsState,
                currentBranch = branchesState.currentBranch,
                refPanelSettings = refPanelSettings,
                selectedItem = selectedItem,
                viewModel = viewModel,
                onDeleteTag = { onNavigate(Screen.TagDelete(it)) },
            )

            stashes(
                stashesState = stashesState,
                selectedItem = selectedItem,
                onExpandStashes = { viewModel.onExpandStashes() },
                onSelectStash = { viewModel.selectStash(it) },
                onApplyStash = { viewModel.applyStash(it) },
                onPopStash = { viewModel.popStash(it) },
                onDeleteStash = { viewModel.deleteStash(it) },
                onConfirmAction = onConfirmAction,
            )

            submodules(
                submodulesState = submodulesState,
                viewModel = viewModel,
                onAddSubmodule = { onNavigate(Screen.SubmoduleAdd) },
                onConfirmAction = onConfirmAction,
            )
        }
    }
}

@Composable
fun FilterTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    AdjustableOutlinedTextField(
        value = value,
        hint = stringResource(Res.string.side_pane_search_hint),
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = LocalTextStyle.current.copy(
            fontSize = MaterialTheme.typography.body2.fontSize,
            color = MaterialTheme.colors.onBackground,
        ),
        singleLine = true,
        leadingIcon = {
            Icon(
                painterResource(Res.drawable.search),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (value.isEmpty()) MaterialTheme.colors.onBackgroundSecondary else MaterialTheme.colors.onBackground
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier
                        .size(16.dp)
                        .handOnHover(),
                ) {
                    Icon(
                        painterResource(Res.drawable.close),
                        contentDescription = null,
                        tint = if (value.isEmpty()) MaterialTheme.colors.onBackgroundSecondary else MaterialTheme.colors.onBackground
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun LazyListScope.localBranches(
    branchesState: BranchesState,
    branchWorktrees: BranchWorktreesState,
    refPanelSettings: RefPanelSettings,
    selectedItem: SelectedItem,
    viewModel: RepositoryOpenViewModel,
    onChangeDefaultUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onNewWorktree: (NewWorktreeStart) -> Unit = {},
    cherryPick: BranchCherryPick? = null,
    commitRewrite: CommitRewrite? = null,
    // Fork-only: the branches selected for deleting several at once (upstream issue 116)
    branchSelection: BranchSelection = BranchSelection(),
    onDeleteBranches: (BranchesDeletionRequest) -> Unit = {},
) {
    val isExpanded = branchesState.isExpanded
    val branches = branchesState.branches
    val currentBranch = branchesState.currentBranch
    val loggedBranch = (selectedItem as? SelectedItem.BranchItem)?.branch?.takeIf { it.isLocal }?.name

    // Fork-only: the section's own actions, in its ⋯ menu and on a right-click of its header (upstream issue 116)
    val onDeleteMergedBranches = { onDeleteBranches(viewModel.mergedBranchesDeletionRequest()) }

    stickyHeader(key = "header:local") {
        SectionHeaderBackground {
            ContextMenu(
                items = { localBranchesContextMenu(onDeleteMergedBranches) }
            ) {
                SideMenuHeader(
                    text = stringResource(Res.string.side_pane_local_branches_title),
                    icon = painterResource(Res.drawable.branch),
                    itemsCount = branches.count(),
                    hoverIcon = null,
                    isExpanded = isExpanded,
                    onExpand = { viewModel.onExpandBranches() },
                    sortAction = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (branchSelection.selected.size >= 2 && branchSelection.group == LOCAL_SELECTION_GROUP) {
                                BranchSelectionChip(
                                    count = branchSelection.selected.size,
                                    onClear = { viewModel.clearBranchSelection() },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }

                            RefSortMenuButton(
                                section = RefSection.Local,
                                sortState = branchesState.sortState,
                                settings = refPanelSettings,
                                onSortChange = { viewModel.onRefSortChanged(RefSection.Local, it) },
                                onKeepHeadOnTopToggle = { viewModel.onKeepHeadOnTopToggled() },
                                onGroupByPrefixToggle = { viewModel.onGroupByPrefixToggled() },
                            )

                            LocalBranchesMenuButton(onDeleteMergedBranches)
                        }
                    },
                )
            }
        }
    }

    if (isExpanded) {
        items(branchesState.rows, key = { it.key }) { row ->
            when (row) {
                is RefRow.Folder -> RefFolder(
                    row,
                    onClick = { viewModel.onRefFolderToggled(row.key) },
                    // Fork-only: the folder's branches, as the search shows them
                    onSelectBranches = {
                        val pinned = currentBranch.takeIf { refPanelSettings.keepHeadOnTop }

                        viewModel.selectBranches(branchesInFolder(branches, row.label, pinned))
                    },
                )
                is RefRow.Item -> {
                    val branch = row.entry.item
                    val scope = rememberCoroutineScope()
                    val clipboard = LocalClipboard.current

                    Branch(
                        branch = branch,
                        displayName = row.displayName,
                        depth = row.depth,
                        ageLabel = row.ageLabel,
                        isSelectedItem = branchSelection.isHighlighted(branch.name, loggedBranch),
                        currentBranch = currentBranch,
                        worktreeUsers = branchWorktrees.usersOf(branch),
                        worktreesBaseBranch = branchWorktrees.baseBranch,
                        worktreesBase = branchWorktrees.baseChoice,
                        // Fork-only: a click with Ctrl, Cmd or Shift changes the selection (upstream issue 116)
                        onBranchClicked = { click ->
                            viewModel.onBranchClicked(branch, click) {
                                branchesState.rows.filterIsInstance<RefRow.Item<Branch>>().map { it.entry.item.name }
                            }
                        },
                        onBranchDoubleClicked = { viewModel.checkoutBranch(branch) },
                        selectedCount = if (branchSelection.isMultiSelectionOf(branch.name)) {
                            branchSelection.selected.size
                        } else {
                            0
                        },
                        onDeleteSelected = { onDeleteBranches(viewModel.selectedBranchesDeletionRequest()) },
                        onClearSelection = { viewModel.clearBranchSelection() },
                        onCheckoutBranch = { viewModel.checkoutBranch(it) },
                        onMergeBranch = { viewModel.mergeBranch(it) },
                        onRebaseBranch = { viewModel.rebaseBranch(it) },
                        onDeleteBranch = { onDeleteBranch(it) },
                        onChangeDefaultUpstreamBranch = { onChangeDefaultUpstreamBranch(it) },
                        onRenameBranch = { onRenameBranch(it) },
                        onChooseWorktreesBase = { viewModel.chooseWorktreesBase(it) },
                        onSwitchToWorktree = { viewModel.switchToWorktree(it) },
                        onNewWorktree = { onNewWorktree(NewWorktreeStart.LocalBranch(branch)) },
                        onCompareWithCurrent = { viewModel.compareBranchWithCurrent(branch) },
                        onCompareWith = { viewModel.compareBranchWith(branch) },
                        onCompareWithWorkingTree = { viewModel.compareBranchWithWorkingTree(branch) },
                        cherryPickItem = cherryPick?.itemFor(branch),
                        onCherryPick = { item -> cherryPick?.onCherryPick?.invoke(branch, item) },
                        onCopyBranchNameToClipboard = {
                            scope.launch {
                                clipboard.setClipboardText(it.simpleName)
                            }
                        },
                        commitRewrite = commitRewrite,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.remotes(
    remotesState: RemotesState,
    worktreesBase: WorktreeBaseBranch?,
    refPanelSettings: RefPanelSettings,
    viewModel: RepositoryOpenViewModel,
    onShowAddEditRemoteDialog: (Remote?) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    onNewWorktree: (NewWorktreeStart) -> Unit = {},
    cherryPick: BranchCherryPick? = null,
    // Fork-only: one remote's branches selected for deleting several at once on the server (follows upstream issue 116)
    selectedItem: SelectedItem? = null,
    branchSelection: BranchSelection = BranchSelection(),
    onDeleteBranches: (BranchesDeletionRequest) -> Unit = {},
) {
    val isExpanded = remotesState.isExpanded
    val remotes = remotesState.remotes
    val loggedBranch = (selectedItem as? SelectedItem.BranchItem)?.branch?.takeIf { it.isRemote }?.name
    val selectedRemote = branchSelection.group?.let(::remoteOfSelectionGroup)

    stickyHeader(key = "header:remotes") {
        SectionHeaderBackground {
            SideMenuHeader(
                text = stringResource(Res.string.side_pane_remotes_title),
                icon = painterResource(Res.drawable.cloud),
                itemsCount = remotes.count(),
                hoverIcon = {
                    IconButton(
                        onClick = { onShowAddEditRemoteDialog(null) },
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(16.dp)
                            .handOnHover(),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.add),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize(),
                            tint = MaterialTheme.colors.onBackground,
                        )
                    }
                },
                isExpanded = isExpanded,
                onExpand = { viewModel.onExpandRemotes() },
                sortAction = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (branchSelection.selected.size >= 2 && selectedRemote != null) {
                            BranchSelectionChip(
                                count = branchSelection.selected.size,
                                onClear = { viewModel.clearBranchSelection() },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }

                        RefSortMenuButton(
                            section = RefSection.Remote,
                            sortState = remotesState.sortState,
                            settings = refPanelSettings,
                            onSortChange = { viewModel.onRefSortChanged(RefSection.Remote, it) },
                            onKeepHeadOnTopToggle = { viewModel.onKeepHeadOnTopToggled() },
                            onGroupByPrefixToggle = { viewModel.onGroupByPrefixToggled() },
                        )
                    }
                },
            )
        }
    }

    if (isExpanded) {
        for (remote in remotes) {
            item(key = "remoteRow:${remote.remoteInfo.remote.name}") {
                Remote(
                    remote = remote,
                    onEditRemote = {
                        val wrapper = remote.remoteInfo.remote
                        onShowAddEditRemoteDialog(wrapper)
                    },
                    onDeleteRemote = {
                        onConfirmAction(ConfirmableAction.DeleteRemote(remote.remoteInfo.remote.name)) {
                            viewModel.deleteRemote(remote.remoteInfo)
                        }
                    },
                    onRemoteClicked = { viewModel.onRemoteClicked(remote) },
                    onFetchBranches = { viewModel.onFetchRemoteBranches(remote) },
                    onDeleteMergedBranches = {
                        onDeleteBranches(viewModel.mergedRemoteBranchesDeletionRequest(remote.remoteInfo.remote.name))
                    },
                )
            }

            if (remote.isExpanded) {
                val remoteName = remote.remoteInfo.remote.name

                items(remote.rows, key = { it.key }) { row ->
                    when (row) {
                        is RefRow.Folder -> RefFolder(
                            row,
                            extraPadding = REMOTE_BRANCH_PADDING,
                            onClick = { viewModel.onRefFolderToggled(row.key) },
                            // Fork-only: the folder's branches, as the search shows them
                            onSelectBranches = {
                                viewModel.selectBranches(branchesInFolder(remote.remoteInfo.branchesList, row.label, null))
                            },
                        )
                        is RefRow.Item -> {
                            val remoteBranch = row.entry.item
                            val scope = rememberCoroutineScope()
                            val clipboard = LocalClipboard.current
                            RemoteBranches(
                                remoteBranch = remoteBranch,
                                displayName = row.displayName,
                                depth = row.depth,
                                ageLabel = row.ageLabel,
                                currentBranch = remotesState.currentBranch,
                                worktreesBase = worktreesBase,
                                isSelectedItem = branchSelection.isHighlighted(remoteBranch.name, loggedBranch),
                                // Fork-only: a click with Ctrl, Cmd or Shift changes the selection
                                onBranchClicked = { click ->
                                    viewModel.onBranchClicked(remoteBranch, click) {
                                        remote.rows.filterIsInstance<RefRow.Item<Branch>>().map { it.entry.item.name }
                                    }
                                },
                                selectedCount = if (branchSelection.isMultiSelectionOf(remoteBranch.name)) {
                                    branchSelection.selected.size
                                } else {
                                    0
                                },
                                remoteName = remoteName,
                                onDeleteSelected = { onDeleteBranches(viewModel.selectedBranchesDeletionRequest()) },
                                onClearSelection = { viewModel.clearBranchSelection() },
                                onCheckoutBranch = { viewModel.checkoutRemoteBranch(it) },
                                onDeleteBranch = {
                                    onConfirmAction(ConfirmableAction.DeleteRemoteBranch(it)) {
                                        viewModel.deleteRemoteBranch(it)
                                    }
                                },
                                onPushRemoteBranch = { viewModel.pushToRemoteBranch(it) },
                                onPullRemoteBranch = { viewModel.pullFromRemoteBranch(it) },
                                onRebaseRemoteBranch = { viewModel.rebaseBranch(it) },
                                onMergeRemoteBranch = { viewModel.mergeBranch(it) },
                                onChooseWorktreesBase = { viewModel.chooseWorktreesBase(it) },
                                onNewWorktree = { onNewWorktree(NewWorktreeStart.RemoteBranch(remoteBranch)) },
                                onCompareWithCurrent = { viewModel.compareBranchWithCurrent(remoteBranch) },
                                onCompareWith = { viewModel.compareBranchWith(remoteBranch) },
                                onCompareWithWorkingTree = { viewModel.compareBranchWithWorkingTree(remoteBranch) },
                                cherryPickItem = cherryPick?.itemFor(remoteBranch),
                                onCherryPick = { item -> cherryPick?.onCherryPick?.invoke(remoteBranch, item) },
                                onCopyBranchNameToClipboard = {
                                    scope.launch {
                                        clipboard.setClipboardText(it.simpleName)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.tags(
    tagsState: TagsState,
    // Fork-only: what "Compare with main (current)" names
    currentBranch: Branch?,
    refPanelSettings: RefPanelSettings,
    viewModel: RepositoryOpenViewModel,
    selectedItem: SelectedItem,
    onDeleteTag: (Tag) -> Unit,
) {
    val isExpanded = tagsState.isExpanded
    val tags = tagsState.tags

    stickyHeader(key = "header:tags") {
        SectionHeaderBackground {
            ContextMenu(
                items = { emptyList() }
            ) {
                SideMenuHeader(
                    text = stringResource(Res.string.side_pane_tags_title),
                    icon = painterResource(Res.drawable.tag),
                    itemsCount = tags.count(),
                    hoverIcon = null,
                    isExpanded = isExpanded,
                    onExpand = { viewModel.onExpandTags() },
                    sortAction = {
                        RefSortMenuButton(
                            section = RefSection.Tags,
                            sortState = tagsState.sortState,
                            settings = refPanelSettings,
                            onSortChange = { viewModel.onRefSortChanged(RefSection.Tags, it) },
                            onKeepHeadOnTopToggle = { viewModel.onKeepHeadOnTopToggled() },
                            onGroupByPrefixToggle = { viewModel.onGroupByPrefixToggled() },
                        )
                    },
                )
            }
        }
    }

    if (isExpanded) {
        items(tagsState.rows, key = { it.key }) { row ->
            when (row) {
                is RefRow.Folder -> RefFolder(row, onClick = { viewModel.onRefFolderToggled(row.key) })
                is RefRow.Item -> {
                    val tag = row.entry.item

                    Tag(
                        tag,
                        displayName = row.displayName,
                        depth = row.depth,
                        ageLabel = row.ageLabel,
                        isSelected = selectedItem is SelectedItem.TagItem && selectedItem.tag == tag,
                        onTagClicked = { viewModel.selectTag(tag) },
                        onCheckoutTag = { viewModel.checkoutTagCommit(tag) },
                        onDeleteTag = { onDeleteTag(tag) },
                        currentBranch = currentBranch,
                        onCompareWithCurrent = { viewModel.compareTagWithCurrent(tag) },
                        onCompareWith = { viewModel.compareTagWith(tag) },
                        onCompareWithWorkingTree = { viewModel.compareTagWithWorkingTree(tag) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.stashes(
    stashesState: StashesState,
    selectedItem: SelectedItem,
    onExpandStashes: () -> Unit,
    onSelectStash: (Commit) -> Unit,
    onApplyStash: (Commit) -> Unit,
    onPopStash: (Commit) -> Unit,
    onDeleteStash: (Commit) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
) {
    val isExpanded = stashesState.isExpanded
    val stashes = stashesState.stashes

    stickyHeader(key = "header:stashes") {
        SectionHeaderBackground {
            ContextMenu(
                items = { emptyList() }
            ) {
                SideMenuHeader(
                    text = stringResource(Res.string.side_pane_stashes_title),
                    icon = painterResource(Res.drawable.stash),
                    itemsCount = stashes.count(),
                    hoverIcon = null,
                    isExpanded = isExpanded,
                    onExpand = onExpandStashes,
                )
            }
        }
    }

    if (isExpanded) {
        items(stashes, key = { it.hash }) { stash ->
            Stash(
                stash,
                isSelected = selectedItem is SelectedItem.CommitItem && selectedItem.isStash && selectedItem.commit.hash == stash.hash,
                onClick = { onSelectStash(stash) },
                onApply = { onApplyStash(stash) },
                onPop = { onPopStash(stash) },
                onDelete = {
                    onConfirmAction(ConfirmableAction.DropStash(stash)) { onDeleteStash(stash) }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.submodules(
    submodulesState: SubmodulesState,
    viewModel: RepositoryOpenViewModel,
    onAddSubmodule: () -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
) {
    val isExpanded = submodulesState.isExpanded
    val submodules = submodulesState.submodules

    stickyHeader(key = "header:submodules") {
        SectionHeaderBackground {
            ContextMenu(
                items = { emptyList() }
            ) {
                SideMenuHeader(
                    text = stringResource(Res.string.side_pane_submodules_title),
                    icon = painterResource(Res.drawable.topic),
                    itemsCount = submodules.count(),
                    hoverIcon = {
                        IconButton(
                            onClick = onAddSubmodule,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(16.dp)
                                .handOnHover(),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.add),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize(),
                                tint = MaterialTheme.colors.onBackground,
                            )
                        }
                    },
                    isExpanded = isExpanded,
                    onExpand = { viewModel.onExpandSubmodules() }
                )
            }
        }
    }

    if (isExpanded) {
        items(submodules, key = { it.first }) { submodule ->
            Submodule(
                submodule = submodule,
                onInitializeSubmodule = { viewModel.initializeSubmodule(submodule.first) },
//                onDeinitializeSubmodule = { submodulesViewModel.onDeinitializeSubmodule(submodule.first) },
                onSyncSubmodule = { viewModel.syncSubmodule(submodule.first) },
                onUpdateSubmodule = { viewModel.updateSubmodule(submodule.first) },
                onOpenSubmoduleInTab = { viewModel.onOpenSubmoduleInTab(submodule.first) },
                onDeleteSubmodule = {
                    onConfirmAction(ConfirmableAction.DeleteSubmodule(submodule.first)) {
                        viewModel.deleteSubmodule(submodule.first)
                    }
                },
            )
        }
    }
}

/** The selection group of the local branches, as [selectionGroupOf] names it. */
private val LOCAL_SELECTION_GROUP = selectionGroupOf("refs/heads/")

/** Left padding of remote branches, which sit under their remote's row. */
private val REMOTE_BRANCH_PADDING = 24.dp

/** Extra left padding of refs inside a folder. */
private val FOLDER_DEPTH_PADDING = 22.dp

/** Opaque background so rows don't show through a pinned section header. */
@Composable
internal fun SectionHeaderBackground(content: @Composable () -> Unit) {
    Box(modifier = Modifier.background(MaterialTheme.colors.background)) {
        content()
    }
}

@Composable
private fun RefFolder(
    folder: RefRow.Folder,
    extraPadding: Dp = 0.dp,
    onClick: () -> Unit,
    // Fork-only: "Select its N branches", for the local branches and a remote's (upstream issue 116)
    onSelectBranches: (() -> Unit)? = null,
) {
    ContextMenu(
        enabled = onSelectBranches != null,
        items = { branchFolderContextMenuItems(folder.count, onSelectBranches ?: {}) },
    ) {
        Row(
            modifier = Modifier
                .height(MaterialTheme.linesHeight.sidePanelItemHeight)
                .fillMaxWidth()
                .handMouseClickable { onClick() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderEntryContent(
                label = folder.label,
                count = folder.count,
                isExpanded = folder.isExpanded,
                startPadding = 14.dp + extraPadding,
            )
        }
    }
}

/** A compact age such as `3d`, or the HEAD label of the current branch when no date sort is active. */
@Composable
private fun RefTrailingLabel(ageLabel: String?, isCurrentBranch: Boolean) {
    val text = ageLabel ?: if (isCurrentBranch) {
        stringResource(Res.string.side_pane_local_branches_current_branch_label)
    } else {
        return
    }

    Text(
        text = text,
        color = MaterialTheme.colors.onBackgroundSecondary,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** `internal` for its test, which renders rows with a selection (fork-only, upstream issue 116). */
@Composable
internal fun Branch(
    branch: Branch,
    displayName: String,
    depth: Int,
    ageLabel: String?,
    currentBranch: Branch?,
    worktreeUsers: BranchWorktreeUsers?,
    worktreesBaseBranch: String?,
    worktreesBase: WorktreeBaseBranch?,
    isSelectedItem: Boolean,
    onBranchClicked: (BranchClick) -> Unit,
    onBranchDoubleClicked: () -> Unit,
    // Fork-only: how many branches are selected when this one is among two or more, else 0 (upstream issue 116)
    selectedCount: Int,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onCheckoutBranch: (Branch) -> Unit,
    onMergeBranch: (Branch) -> Unit,
    onRebaseBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onChangeDefaultUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onChooseWorktreesBase: (branch: String?) -> Unit,
    onSwitchToWorktree: (Worktree) -> Unit,
    onNewWorktree: () -> Unit,
    onCompareWithCurrent: () -> Unit,
    onCompareWith: () -> Unit,
    onCompareWithWorkingTree: () -> Unit,
    cherryPickItem: BranchCherryPickItem?,
    onCherryPick: (BranchCherryPickItem) -> Unit,
    onCopyBranchNameToClipboard: (Branch) -> Unit,
    // Fork-only: "Squash branch into one commit…"
    commitRewrite: CommitRewrite? = null,
) {
    val isCurrentBranch = currentBranch?.name == branch.name
    val clickNow = rememberClickNow()

    ContextMenu(
        items = {
            if (selectedCount >= 2) {
                return@ContextMenu selectedBranchesContextMenuItems(selectedCount, onDeleteSelected, onClearSelection)
            }

            branchContextMenuItems(
                branch = branch,
                currentBranch = currentBranch,
                isCurrentBranch = isCurrentBranch,
                isLocal = true,
                onCheckoutBranch = onCheckoutBranch,
                onMergeBranch = onMergeBranch,
                onDeleteBranch = onDeleteBranch,
                onRebaseBranch = onRebaseBranch,
                onPushToRemoteBranch = {},
                onPullFromRemoteBranch = {},
                onChangeDefaultUpstreamBranch = onChangeDefaultUpstreamBranch,
                onRenameBranch = onRenameBranch,
                onCopyBranchNameToClipboard = onCopyBranchNameToClipboard,
                worktreeUsers = worktreeUsers,
                worktreesBase = worktreesBase,
                onChooseWorktreesBase = onChooseWorktreesBase,
                onSwitchToWorktree = onSwitchToWorktree,
                onNewWorktree = onNewWorktree,
                onCompareWithCurrent = onCompareWithCurrent,
                onCompareWith = onCompareWith,
                onCompareWithWorkingTree = onCompareWithWorkingTree,
                cherryPick = cherryPickItem,
                onCherryPick = onCherryPick,
                squashBranch = commitRewrite?.branchItem(branch.name),
                editBranch = commitRewrite?.branchEditorItem(branch.name),
            )
        }
    ) {
        val fontWeight = if (isCurrentBranch) FontWeight.Bold else FontWeight.Normal

        SideMenuSubentry(
            text = displayName,
            fontWeight = fontWeight,
            iconResourcePath = Res.drawable.branch,
            isSelected = isSelectedItem,
            extraPadding = FOLDER_DEPTH_PADDING * depth,
            onClick = { onBranchClicked(clickNow()) },
            // Checking a branch out takes a plain double-click: with Ctrl, Cmd or Shift it's two selecting clicks
            onDoubleClick = { if (clickNow() == BranchClick.Plain) onBranchDoubleClicked() },
            textContent = {
                // The branch name first, then the worktree that uses it (fork-only)
                BranchNameWithWorktree(
                    text = displayName,
                    fontWeight = fontWeight,
                    branchName = branch.name,
                    users = worktreeUsers,
                    baseBranch = worktreesBaseBranch,
                    hasTrailingLabel = ageLabel != null || isCurrentBranch,
                    modifier = Modifier.weight(1f),
                )

                RefTrailingLabel(ageLabel, isCurrentBranch)
            },
        )
    }
}


/**
 * What a click on a branch does, read from the keys held when it happens, not when the row was last composed
 * (fork-only, upstream issue 116).
 */
@Composable
private fun rememberClickNow(): () -> BranchClick {
    val windowInfo = LocalWindowInfo.current

    return {
        val modifiers = windowInfo.keyboardModifiers

        branchClickOf(
            isShiftPressed = modifiers.isShiftPressed,
            isCtrlPressed = modifiers.isCtrlPressed,
            isMetaPressed = modifiers.isMetaPressed,
            isMac = currentOs.isMac(),
        )
    }
}

@Composable
private fun Remote(
    remote: RemoteView,
    onEditRemote: () -> Unit,
    onDeleteRemote: () -> Unit,
    onFetchBranches: () -> Unit,
    onRemoteClicked: () -> Unit,
    onDeleteMergedBranches: () -> Unit,
) {
    ContextMenu(
        items = {
            remoteContextMenu(
                onEdit = onEditRemote,
                onDelete = onDeleteRemote,
                onFetch = onFetchBranches,
                remoteName = remote.remoteInfo.remote.name,
                onDeleteMergedBranches = onDeleteMergedBranches,
            )
        }
    ) {
        SideMenuSubentry(
            text = remote.remoteInfo.remote.name,
            iconResourcePath = Res.drawable.cloud,
            onClick = onRemoteClicked,
            isSelected = false,
        )
    }
}


/** `internal` for its test, which renders rows with a selection (fork-only, follows upstream issue 116). */
@Composable
internal fun RemoteBranches(
    remoteBranch: Branch,
    displayName: String,
    depth: Int,
    ageLabel: String?,
    currentBranch: Branch?,
    worktreesBase: WorktreeBaseBranch?,
    isSelectedItem: Boolean,
    onBranchClicked: (BranchClick) -> Unit,
    // Fork-only: how many of the remote's branches are selected when this one is among two or more, else 0
    selectedCount: Int,
    remoteName: String,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onCheckoutBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onPushRemoteBranch: (Branch) -> Unit,
    onPullRemoteBranch: (Branch) -> Unit,
    onRebaseRemoteBranch: (Branch) -> Unit,
    onMergeRemoteBranch: (Branch) -> Unit,
    onChooseWorktreesBase: (branch: String?) -> Unit,
    onNewWorktree: () -> Unit,
    onCompareWithCurrent: () -> Unit,
    onCompareWith: () -> Unit,
    onCompareWithWorkingTree: () -> Unit,
    cherryPickItem: BranchCherryPickItem?,
    onCherryPick: (BranchCherryPickItem) -> Unit,
    onCopyBranchNameToClipboard: (Branch) -> Unit,
) {
    val clickNow = rememberClickNow()

    ContextMenu(
        items = {
            if (selectedCount >= 2) {
                return@ContextMenu selectedBranchesContextMenuItems(
                    count = selectedCount,
                    onDelete = onDeleteSelected,
                    onClearSelection = onClearSelection,
                    remote = remoteName,
                )
            }

            branchContextMenuItems(
                branch = remoteBranch,
                currentBranch = currentBranch,
                isCurrentBranch = false,
                isLocal = false,
                onCheckoutBranch = onCheckoutBranch,
                onMergeBranch = onMergeRemoteBranch,
                onDeleteBranch = {},
                onDeleteRemoteBranch = onDeleteBranch,
                onRebaseBranch = onRebaseRemoteBranch,
                onPushToRemoteBranch = onPushRemoteBranch,
                onPullFromRemoteBranch = onPullRemoteBranch,
                onChangeDefaultUpstreamBranch = {},
                onRenameBranch = {},
                onCopyBranchNameToClipboard = onCopyBranchNameToClipboard,
                worktreesBase = worktreesBase,
                onChooseWorktreesBase = onChooseWorktreesBase,
                onNewWorktree = onNewWorktree,
                onCompareWithCurrent = onCompareWithCurrent,
                onCompareWith = onCompareWith,
                onCompareWithWorkingTree = onCompareWithWorkingTree,
                cherryPick = cherryPickItem,
                onCherryPick = onCherryPick,
            )
        }
    ) {
        SideMenuSubentry(
            text = displayName,
            extraPadding = REMOTE_BRANCH_PADDING + FOLDER_DEPTH_PADDING * depth,
            isSelected = isSelectedItem,
            iconResourcePath = Res.drawable.branch,
            onClick = { onBranchClicked(clickNow()) },
            // Checking a branch out takes a plain double-click: with Ctrl, Cmd or Shift it's two selecting clicks
            onDoubleClick = { if (clickNow() == BranchClick.Plain) onCheckoutBranch(remoteBranch) },
        ) {
            RefTrailingLabel(ageLabel, isCurrentBranch = false)
        }
    }
}

@Composable
private fun Tag(
    tag: Tag,
    displayName: String,
    depth: Int,
    ageLabel: String?,
    isSelected: Boolean,
    onTagClicked: () -> Unit,
    onCheckoutTag: () -> Unit,
    onDeleteTag: () -> Unit,
    currentBranch: Branch?,
    onCompareWithCurrent: () -> Unit,
    onCompareWith: () -> Unit,
    onCompareWithWorkingTree: () -> Unit,
) {
    ContextMenu(
        items = {
            tagContextMenuItems(
                onCheckoutTag = onCheckoutTag,
                onDeleteTag = onDeleteTag,
                currentBranch = currentBranch,
                onCompareWithCurrent = onCompareWithCurrent,
                onCompareWith = onCompareWith,
                onCompareWithWorkingTree = onCompareWithWorkingTree,
            )
        }
    ) {
        SideMenuSubentry(
            text = displayName,
            isSelected = isSelected,
            iconResourcePath = Res.drawable.tag,
            extraPadding = FOLDER_DEPTH_PADDING * depth,
            onClick = onTagClicked,
        ) {
            RefTrailingLabel(ageLabel, isCurrentBranch = false)
        }
    }
}


@Composable
private fun Stash(
    stash: Commit,
    isSelected: Boolean,
    onClick: () -> Unit,
    onApply: () -> Unit,
    onPop: () -> Unit,
    onDelete: () -> Unit,
) {
    ContextMenu(
        items = {
            stashesContextMenuItems(
                onApply = onApply,
                onPop = onPop,
                onDelete = onDelete,
            )
        }
    ) {
        SideMenuSubentry(
            text = stash.shortMessage,
            isSelected = isSelected,
            iconResourcePath = Res.drawable.stash,
            onClick = onClick,
        )
    }
}

@Composable
private fun Submodule(
    submodule: Pair<String, Submodule>,
    onInitializeSubmodule: () -> Unit,
//    onDeinitializeSubmodule: () -> Unit,
    onSyncSubmodule: () -> Unit,
    onUpdateSubmodule: () -> Unit,
    onOpenSubmoduleInTab: () -> Unit,
    onDeleteSubmodule: () -> Unit,
) {
    ContextMenu(
        items = {
            submoduleContextMenuItems(
                submodule.second,
                onInitializeSubmodule = onInitializeSubmodule,
//                onDeinitializeSubmodule = onDeinitializeSubmodule,
                onSyncSubmodule = onSyncSubmodule,
                onUpdateSubmodule = onUpdateSubmodule,
                onOpenSubmoduleInTab = onOpenSubmoduleInTab,
                onDeleteSubmodule = onDeleteSubmodule,
            )
        }
    ) {
        SideMenuSubentry(
            text = submodule.first,
            iconResourcePath = Res.drawable.topic,
            isSelected = false,
            onClick = {
                if (submodule.second.state.isValid) {
                    onOpenSubmoduleInTab()
                }
            },
        ) {
            val stateName = submodule.second.state.toString()
            DelayedTooltip(stateName) {
                Text(
                    text = stateName.first().toString(),
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}