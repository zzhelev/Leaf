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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.Screen
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.extensions.isValid
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.models.Submodule
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.models.ui.SelectedItem
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
    val refPanelSettings by viewModel.refPanelSettings.collectAsState()

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
                refPanelSettings = refPanelSettings,
                selectedItem = selectedItem,
                viewModel = viewModel,
                onChangeDefaultUpstreamBranch = { onNavigate(Screen.BranchChangeUpstream(it)) },
                onRenameBranch = { onNavigate(Screen.BranchRename(it)) },
                onDeleteBranch = { onNavigate(Screen.BranchDelete(it)) },
            )

            worktrees(
                worktreesState = worktreesState,
                onExpand = { viewModel.onExpandWorktrees() },
                onWorktreeClicked = { viewModel.selectWorktree(it.worktree) },
                onCopyPath = { scope.launch { clipboard.setClipboardText(it.worktree.path) } },
            )

            remotes(
                remotesState = remotesState,
                refPanelSettings = refPanelSettings,
                viewModel = viewModel,
                onShowAddEditRemoteDialog = { onNavigate(Screen.AddEditRemote(it)) },
                onConfirmAction = onConfirmAction,
            )

            tags(
                tagsState = tagsState,
                refPanelSettings = refPanelSettings,
                selectedItem = selectedItem,
                viewModel = viewModel,
                onDeleteTag = { onNavigate(Screen.TagDelete(it)) },
            )

            stashes(
                stashesState = stashesState,
                selectedItem = selectedItem,
                viewModel = viewModel,
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
    refPanelSettings: RefPanelSettings,
    selectedItem: SelectedItem,
    viewModel: RepositoryOpenViewModel,
    onChangeDefaultUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
) {
    val isExpanded = branchesState.isExpanded
    val branches = branchesState.branches
    val currentBranch = branchesState.currentBranch

    stickyHeader(key = "header:local") {
        SectionHeaderBackground {
            ContextMenu(
                items = { emptyList() }
            ) {
                SideMenuHeader(
                    text = stringResource(Res.string.side_pane_local_branches_title),
                    icon = painterResource(Res.drawable.branch),
                    itemsCount = branches.count(),
                    hoverIcon = null,
                    isExpanded = isExpanded,
                    onExpand = { viewModel.onExpandBranches() },
                    sortAction = {
                        RefSortMenuButton(
                            section = RefSection.Local,
                            sortState = branchesState.sortState,
                            settings = refPanelSettings,
                            onSortChange = { viewModel.onRefSortChanged(RefSection.Local, it) },
                            onKeepHeadOnTopToggle = { viewModel.onKeepHeadOnTopToggled() },
                            onGroupByPrefixToggle = { viewModel.onGroupByPrefixToggled() },
                        )
                    },
                )
            }
        }
    }

    if (isExpanded) {
        items(branchesState.rows, key = { it.key }) { row ->
            when (row) {
                is RefRow.Folder -> RefFolder(row, onClick = { viewModel.onRefFolderToggled(row.key) })
                is RefRow.Item -> {
                    val branch = row.entry.item
                    val scope = rememberCoroutineScope()
                    val clipboard = LocalClipboard.current

                    Branch(
                        branch = branch,
                        displayName = row.displayName,
                        depth = row.depth,
                        ageLabel = row.ageLabel,
                        isSelectedItem = selectedItem is SelectedItem.BranchItem && selectedItem.branch == branch,
                        currentBranch = currentBranch,
                        onBranchClicked = { viewModel.selectBranch(branch) },
                        onBranchDoubleClicked = { viewModel.checkoutBranch(branch) },
                        onCheckoutBranch = { viewModel.checkoutBranch(branch) },
                        onMergeBranch = { viewModel.mergeBranch(branch) },
                        onRebaseBranch = { viewModel.rebaseBranch(branch) },
                        onDeleteBranch = { onDeleteBranch(branch) },
                        onChangeDefaultUpstreamBranch = { onChangeDefaultUpstreamBranch(branch) },
                        onRenameBranch = { onRenameBranch(branch) },
                        onCopyBranchNameToClipboard = {
                            scope.launch {
                                clipboard.setClipboardText(branch.simpleName)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.remotes(
    remotesState: RemotesState,
    refPanelSettings: RefPanelSettings,
    viewModel: RepositoryOpenViewModel,
    onShowAddEditRemoteDialog: (Remote?) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
) {
    val isExpanded = remotesState.isExpanded
    val remotes = remotesState.remotes

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
                    RefSortMenuButton(
                        section = RefSection.Remote,
                        sortState = remotesState.sortState,
                        settings = refPanelSettings,
                        onSortChange = { viewModel.onRefSortChanged(RefSection.Remote, it) },
                        onKeepHeadOnTopToggle = { viewModel.onKeepHeadOnTopToggled() },
                        onGroupByPrefixToggle = { viewModel.onGroupByPrefixToggled() },
                    )
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
                )
            }

            if (remote.isExpanded) {
                items(remote.rows, key = { it.key }) { row ->
                    when (row) {
                        is RefRow.Folder -> RefFolder(
                            row,
                            extraPadding = REMOTE_BRANCH_PADDING,
                            onClick = { viewModel.onRefFolderToggled(row.key) },
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
                                onBranchClicked = { viewModel.selectBranch(remoteBranch) },
                                onCheckoutBranch = { viewModel.checkoutRemoteBranch(remoteBranch) },
                                onDeleteBranch = {
                                    onConfirmAction(ConfirmableAction.DeleteRemoteBranch(remoteBranch)) {
                                        viewModel.deleteRemoteBranch(remoteBranch)
                                    }
                                },
                                onPushRemoteBranch = { viewModel.pushToRemoteBranch(remoteBranch) },
                                onPullRemoteBranch = { viewModel.pullFromRemoteBranch(remoteBranch) },
                                onRebaseRemoteBranch = { viewModel.rebaseBranch(remoteBranch) },
                                onMergeRemoteBranch = { viewModel.mergeBranch(remoteBranch) },
                                onCopyBranchNameToClipboard = {
                                    scope.launch {
                                        clipboard.setClipboardText(remoteBranch.simpleName)
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
                        onDeleteTag = { onDeleteTag(tag) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.stashes(
    stashesState: StashesState,
    viewModel: RepositoryOpenViewModel,
    selectedItem: SelectedItem,
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
                    onExpand = { viewModel.onExpandStashes() }
                )
            }
        }
    }

    if (isExpanded) {
        items(stashes, key = { it.hash }) { stash ->
            Stash(
                stash,
                isSelected = selectedItem is SelectedItem.CommitItem && selectedItem.isStash && selectedItem.commit.hash == stash.hash,
                onClick = { viewModel.selectStash(stash) },
                onApply = { viewModel.applyStash(stash) },
                onPop = { viewModel.popStash(stash) },
                onDelete = {
                    onConfirmAction(ConfirmableAction.DropStash(stash)) { viewModel.deleteStash(stash) }
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

@Composable
private fun Branch(
    branch: Branch,
    displayName: String,
    depth: Int,
    ageLabel: String?,
    currentBranch: Branch?,
    isSelectedItem: Boolean,
    onBranchClicked: () -> Unit,
    onBranchDoubleClicked: () -> Unit,
    onCheckoutBranch: () -> Unit,
    onMergeBranch: () -> Unit,
    onRebaseBranch: () -> Unit,
    onDeleteBranch: () -> Unit,
    onChangeDefaultUpstreamBranch: () -> Unit,
    onRenameBranch: () -> Unit,
    onCopyBranchNameToClipboard: () -> Unit,
) {
    val isCurrentBranch = currentBranch?.name == branch.name

    ContextMenu(
        items = {
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
                onCopyBranchNameToClipboard = onCopyBranchNameToClipboard
            )
        }
    ) {
        SideMenuSubentry(
            text = displayName,
            fontWeight = if (isCurrentBranch) FontWeight.Bold else FontWeight.Normal,
            iconResourcePath = Res.drawable.branch,
            isSelected = isSelectedItem,
            extraPadding = FOLDER_DEPTH_PADDING * depth,
            onClick = onBranchClicked,
            onDoubleClick = onBranchDoubleClicked,
        ) {
            RefTrailingLabel(ageLabel, isCurrentBranch)
        }
    }
}


@Composable
private fun Remote(
    remote: RemoteView,
    onEditRemote: () -> Unit,
    onDeleteRemote: () -> Unit,
    onFetchBranches: () -> Unit,
    onRemoteClicked: () -> Unit,
) {
    ContextMenu(
        items = {
            remoteContextMenu(
                onEdit = onEditRemote,
                onDelete = onDeleteRemote,
                onFetch = onFetchBranches,
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


@Composable
private fun RemoteBranches(
    remoteBranch: Branch,
    displayName: String,
    depth: Int,
    ageLabel: String?,
    currentBranch: Branch?,
    onBranchClicked: () -> Unit,
    onCheckoutBranch: () -> Unit,
    onDeleteBranch: () -> Unit,
    onPushRemoteBranch: () -> Unit,
    onPullRemoteBranch: () -> Unit,
    onRebaseRemoteBranch: () -> Unit,
    onMergeRemoteBranch: () -> Unit,
    onCopyBranchNameToClipboard: () -> Unit,
) {
    ContextMenu(
        items = {
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
                onCopyBranchNameToClipboard = onCopyBranchNameToClipboard
            )
        }
    ) {
        SideMenuSubentry(
            text = displayName,
            extraPadding = REMOTE_BRANCH_PADDING + FOLDER_DEPTH_PADDING * depth,
            isSelected = false,
            iconResourcePath = Res.drawable.branch,
            onClick = onBranchClicked,
            onDoubleClick = onCheckoutBranch,
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
) {
    ContextMenu(
        items = {
            tagContextMenuItems(
                onCheckoutTag = onCheckoutTag,
                onDeleteTag = onDeleteTag,
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