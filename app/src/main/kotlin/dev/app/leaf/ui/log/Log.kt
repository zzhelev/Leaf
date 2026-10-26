@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package dev.app.leaf.ui.log

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.printLog
import dev.app.leaf.compose.rememberInTab
import dev.app.leaf.domain.BranchesConstants.LOCAL_PREFIX_LENGTH
import dev.app.leaf.domain.log.LogMove
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.RewriteRequest
import dev.app.leaf.domain.log.headCommitHash
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.extensions.*
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.repositoryopen.LogAction
import dev.app.leaf.repositoryopen.LogSearch
import dev.app.leaf.repositoryopen.LogState
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.theme.*
import dev.app.leaf.ui.BranchWorktreeChipMark
import dev.app.leaf.ui.LocalBranchWorktrees
import dev.app.leaf.ui.LocalOnChooseWorktreesBase
import dev.app.leaf.ui.LocalOnNewWorktree
import dev.app.leaf.ui.LocalOnSwitchToWorktree
import dev.app.leaf.ui.compare.CompareLetter
import dev.app.leaf.ui.components.AvatarImage
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltipPosition
import dev.app.leaf.ui.context_menu.*
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.resizePointerIconEast
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.eclipse.jgit.lib.Constants
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private const val MIN_GRAPH_LANES = 2

private const val HORIZONTAL_SCROLL_PIXELS_MULTIPLIER = 10

private const val ARC_DIRECTION_LEFT = -1
private const val ARC_DIRECTION_RIGHT = 1

/**
 * Additional number of lanes to simulate to create a margin at the end of the graph.
 */
private const val MARGIN_GRAPH_LANES = 2
private const val LANE_WIDTH = 14f
private val COMMIT_NODE_SIZE = 10.dp
private val COMMIT_TOOLTIP_AVATAR_SIZE = 20.dp
private const val DIVIDER_WIDTH = 8

private const val LOG_BOTTOM_PADDING = 80

private const val MIN_COMMITS_BEFORE_REQUESTING_MORE = 200

private const val TAG = "LogView"

// TODO Min size for message column
@Composable
fun Log(
    modifier: Modifier,
    viewModel: RepositoryOpenViewModel,
    selectedItem: SelectedItem,
    repositoryState: RepositoryState,
    onCreateBranch: (Commit) -> Unit,
    onResetBranch: (Commit) -> Unit,
    onCreateTag: (Commit) -> Unit,
    onChangeUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onDeleteTag: (Tag) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    onNewWorktree: ((NewWorktreeStart) -> Unit)? = null,
    // Fork-only: opens the dialog that cherry-picks a branch's commits
    onCherryPickCommits: ((Branch) -> Unit)? = null,
    // Fork-only: opens the dialog that squashes or rewords commits (docs/fork/squash-and-rebase.md, A3)
    onRewrite: ((RewriteRequest) -> Unit)? = null,
    // Fork-only: the Drop dialog of a plan that drops a commit (docs/fork/commit-editor.md)
    onDropCommit: ((RebasePlan) -> Unit)? = null,
) {
    val logStatusState = viewModel.logState.collectAsState()
    val logStatus = logStatusState.value
    val logColumns by viewModel.logColumns.collectAsState()
    val branchWorktrees by viewModel.branchWorktrees.collectAsState()
    // Fork-only: a comparison's rows, and what the menus compare a row with (docs/fork/compare.md)
    val comparisonState by viewModel.comparisonState.collectAsState()
    val selectedForComparison by viewModel.selectedForComparison.collectAsState()
    // The working tree's B is the uncommitted changes' row, not its commit's
    val bIsWorkingTree = comparisonState?.bIsWorkingTree == true
    val logComparison = LogComparison(
        aCommit = comparisonState?.aCommit,
        bCommit = comparisonState?.bCommit?.takeIf { !bIsWorkingTree },
        selectedName = selectedForComparison,
        workingTreeIsB = bIsWorkingTree,
    )
    val compareWithCurrent = remember(viewModel, logStatus.currentBranch) {
        CompareWithCurrent(
            currentBranch = logStatus.currentBranch,
            onBranch = viewModel::compareBranchWithCurrent,
            onTag = viewModel::compareTagWithCurrent,
            onBranchWith = viewModel::compareBranchWith,
            onTagWith = viewModel::compareTagWith,
            onBranchWithWorkingTree = viewModel::compareBranchWithWorkingTree,
            onTagWithWorkingTree = viewModel::compareTagWithWorkingTree,
        )
    }

    // Fork-only: the commits selected with Shift (docs/fork/squash-and-rebase.md, A2)
    val selectedCommits by viewModel.selectedCommits.collectAsState()

    // Fork-only: the menus' squash and reword items (A3)
    val commitRewrite = rememberCommitRewrite(viewModel, logStatus.currentBranch, onRewrite, onDropCommit)

    // Fork-only: the branch chips' cherry-pick item
    val branchesAhead by viewModel.branchesAhead.collectAsState()
    val branchCherryPick = remember(viewModel, branchesAhead, logStatus.currentBranch, onCherryPickCommits) {
        onCherryPickCommits?.let { openDialog ->
            BranchCherryPick(branchesAhead, logStatus.currentBranch) { branch, item ->
                viewModel.cherryPickFromBranch(branch, item, openDialog)
            }
        }
    }

    LaunchedEffect(logStatus.verticalScrollState, logStatus.commitList) {
        launch {
            viewModel.focusCommit.collect { commit ->
                scrollToCommit(logStatus.verticalScrollState, logStatus.commitList, commit.commit)
            }
        }
        launch {
            viewModel.scrollToUncommittedChanges.collect {
                scrollToUncommittedChanges(logStatus.verticalScrollState, logStatus.commitList)
            }
        }
    }

    LaunchedEffect(selectedItem) {
        if (
            (selectedItem is SelectedItem.CommitBasedItem && selectedItem !is SelectedItem.CommitItem) ||
            (selectedItem is SelectedItem.CommitItem && selectedItem.scrollToItem)
        ) {
            scrollToCommit(logStatus.verticalScrollState, logStatus.commitList, selectedItem.commit)
        }
    }

    BoxWithConstraints(modifier) {
        CompositionLocalProvider(
            LocalBranchWorktrees provides branchWorktrees,
            LocalOnChooseWorktreesBase provides viewModel::chooseWorktreesBase,
            LocalOnSwitchToWorktree provides viewModel::switchToWorktree,
            LocalOnNewWorktree provides onNewWorktree,
            LocalLogComparison provides logComparison,
            LocalCompareWithCurrent provides compareWithCurrent,
            LocalBranchCherryPick provides branchCherryPick,
            LocalSelectedCommits provides selectedCommits?.hashes.orEmpty(),
            LocalCommitRewrite provides commitRewrite,
        ) {
            LogView(
                logState = logStatus,
                logWidth = maxWidth.value,
                selectedItem = selectedItem,
                repositoryState = repositoryState,
                savedColumns = logColumns,
                onRequestMoreLogItems = { firstVisibleItemIndex -> viewModel.loadMoreLogItems(firstVisibleItemIndex) },
                onCreateBranch = onCreateBranch,
                onResetBranch = onResetBranch,
                onCreateTag = onCreateTag,
                onChangeUpstreamBranch = onChangeUpstreamBranch,
                onRenameBranch = onRenameBranch,
                onDeleteBranch = onDeleteBranch,
                onDeleteTag = onDeleteTag,
                onConfirmAction = onConfirmAction,
                onAction = { viewModel.onAction(it) },
                onMoveSelection = viewModel::moveLogSelection,
                onExtendSelection = viewModel::extendLogSelection,
                searchView = {
                    SearchFilter(
                        logViewModel = viewModel,
                        searchFilterResults = it,
                        searchFocused = { viewModel.addSearchToCloseableView() },
                    )
                }
            )
        }

        AnimatedVisibility(visible = logStatus.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LogView(
    logState: LogState,
    logWidth: Float,
    selectedItem: SelectedItem,
    repositoryState: RepositoryState,
    savedColumns: LogColumnsSettings,
    onRequestMoreLogItems: (Int) -> Unit,
    onCreateBranch: (Commit) -> Unit,
    onResetBranch: (Commit) -> Unit,
    onCreateTag: (Commit) -> Unit,
    onChangeUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onDeleteTag: (Tag) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    onAction: (LogAction) -> Unit,
    onMoveSelection: (LogMove, pageSize: Int, hasUncommittedRow: Boolean) -> Int?,
    onExtendSelection: (step: Int, hasUncommittedRow: Boolean) -> Int?,
    searchView: @Composable (LogSearch.SearchResults) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hasUncommittedChanges = logState.hasUncommittedChanges
    val commitList = logState.commitList

    val verticalScrollState = logState.verticalScrollState
    val horizontalScrollState = logState.horizontalScrollState
    val searchFilterValue = logState.searchFilter

    LaunchedEffect(verticalScrollState, logState) {
        snapshotFlow { verticalScrollState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect {
                val commitsList = logState.commitList

                // TODO Check what would happen with a repo with multiple starting commits
                if (
                    commitsList.commits.isNotEmpty() &&
                    commitsList.commits.count() - it < MIN_COMMITS_BEFORE_REQUESTING_MORE &&
                    commitsList.commits.entries.last().value.commit.parentCount > 0
                ) {
                    printLog(TAG, "Requesting more items")
                    onRequestMoreLogItems(verticalScrollState.firstVisibleItemIndex)
                }
            }
    }

    val selectedCommit = if (selectedItem is SelectedItem.CommitBasedItem) {
        selectedItem.commit
    } else {
        null
    }

    // A local copy follows the dividers while they're dragged, and is saved when a drag ends
    var columnSettings by remember(savedColumns) { mutableStateOf(savedColumns) }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colors.background)
            .fillMaxSize()
    ) {
        val maxLinePosition = if (commitList.isNotEmpty())
            commitList.maxLane
        else
            MIN_GRAPH_LANES

        val lanesWidth = (maxLinePosition + MARGIN_GRAPH_LANES) * LANE_WIDTH
        val graphWidthValue = graphColumnWidth(lanesWidth, columnSettings.graphMaxWidth)

        // Using remember(graphRealWidth, graphWidth) makes the selected background color glitch when changing tabs
        val graphWidth = graphWidthValue.dp
        val graphRealWidth = maxOf(lanesWidth, graphWidthValue).dp

        val onGraphDrag: (Float) -> Unit = { delta ->
            val shownWidth = graphColumnWidth(lanesWidth, columnSettings.graphMaxWidth)
            val maxWidth = draggedGraphMaxWidth(columnSettings.graphMaxWidth, shownWidth, lanesWidth, delta)

            columnSettings = columnSettings.withGraphMaxWidth(maxWidth)
        }
        val onGraphDragStopped = { onAction(LogAction.SetGraphMaxWidth(columnSettings.graphMaxWidth)) }

        // What's left for the message and the columns
        val columnsWidth = logWidth - graphWidthValue - LOG_ROW_END_PADDING.value
        val fittedColumns = columnSettings.fitting(columnsWidth, spacing = LOG_COLUMN_SPACING)
        val columnsLayout = LogColumnsLayout(fittedColumns.shown, columnSettings.dateShowsTime)
        val columnsMenuItems = logColumnsMenuItems(columnSettings, fittedColumns.leftOut, onAction)

        if (searchFilterValue is LogSearch.SearchResults) {
            searchView(searchFilterValue)
        }

        GraphHeader(
            graphWidth = graphWidth,
            columns = fittedColumns.shown,
            columnsMenuItems = columnsMenuItems,
            onGraphDrag = onGraphDrag,
            onGraphDragStopped = onGraphDragStopped,
            onColumnResize = { column, delta ->
                val maxWidth = columnSettings.maxWidthFor(column, columnsWidth, spacing = LOG_COLUMN_SPACING)
                val width = (columnSettings.entry(column).width - delta).coerceAtMost(maxWidth)

                columnSettings = columnSettings.resized(column, width)
            },
            onColumnResizeFinished = { column ->
                onAction(LogAction.ResizeColumn(column, columnSettings.entry(column).width))
            },
            onShowSearch = {
                onAction(LogAction.SearchValueChange(""))
            }
        )

        Box {

            // This Box is only used to get a scroll state. With this scroll state we will manually add an offset in
            // the messages list
            Box(
                Modifier
                    .width(graphWidth)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScrollState)
                    .padding(bottom = 8.dp)
            ) {
                // The content has to be bigger in order to show the scroll bar in the parent component
                Box(
                    modifier = Modifier.width(graphRealWidth)
                )
            }

            CommitsList(
                scrollState = verticalScrollState,
                horizontalScrollState = horizontalScrollState,
                hasUncommittedChanges = hasUncommittedChanges,
                searchFilter = (searchFilterValue as? LogSearch.SearchResults)?.matchingCommits,
                focusedSearchMatch = (searchFilterValue as? LogSearch.SearchResults)?.focusedCommit?.hash,
                selectedCommit = selectedCommit,
                logState = logState,
                repositoryState = repositoryState,
                selectedItem = selectedItem,
                commitList = commitList,
                branches = logState.branches,
                tags = logState.tags,
                stashes = logState.stashes,
                graphWidth = graphWidth,
                columnsLayout = columnsLayout,
                onCreateBranch = onCreateBranch,
                onResetBranch = onResetBranch,
                onCreateTag = onCreateTag,
                onChangeUpstreamBranch = onChangeUpstreamBranch,
                onRenameBranch = onRenameBranch,
                onDeleteBranch = onDeleteBranch,
                onDeleteTag = onDeleteTag,
                onConfirmAction = onConfirmAction,
                onAction = onAction,
                onMoveSelection = onMoveSelection,
                onExtendSelection = onExtendSelection,
            )

            val density = LocalDensity.current.density
            DividerLog(
                modifier = Modifier.draggable(
                    rememberDraggableState {
                        onGraphDrag(it / density)
                    },
                    Orientation.Horizontal,
                    onDragStopped = { onGraphDragStopped() },
                ),
                graphWidth = graphWidth,
            )


            // Scrollbar used to scroll horizontally the graph nodes
            // Added after every component to have the highest priority when clicking
            HorizontalScrollbar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .width(graphWidth)
                    .padding(start = 4.dp, bottom = 4.dp), style = LocalScrollbarStyle.current.copy(
                    unhoverColor = MaterialTheme.colors.scrollbarNormal,
                    hoverColor = MaterialTheme.colors.scrollbarHover,
                ),
                adapter = rememberScrollbarAdapter(horizontalScrollState)
            )

            val isFirstItemVisible by remember(verticalScrollState) {
                derivedStateOf { verticalScrollState.firstVisibleItemIndex > 0 }
            }

            if (isFirstItemVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .handMouseClickable {
                            scope.launch {
                                verticalScrollState.scrollToItem(0)
                            }
                        }
                        .background(MaterialTheme.colors.primary)
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(Res.drawable.align_top),
                            contentDescription = null,
                            tint = MaterialTheme.colors.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )

                        Text(
                            text = "Scroll to top",
                            modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colors.onPrimary,
                            maxLines = 1,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }
            }
        }
    }
}

suspend fun scrollToCommit(
    verticalScrollState: LazyListState,
    commitList: GraphCommits,
    commit: Commit,
) {
    val index = commitList.commits.entries.indexOfFirst { it.value.hash == commit?.hash }
    // TODO Show a message informing the user why we aren't scrolling
    // Index can be -1 if the ref points to a commit that is not shown in the graph due to the limited
    // number of displayed commits.
    // Fork-only fix: a line's key starts with its commit's hash (see CommitsList). Upstream looked for the message,
    // which no key has, and which every key has when it's empty
    val isCommitAlreadyVisible = verticalScrollState.layoutInfo.visibleItemsInfo.any {
        (it.key as? String)?.startsWith(commit.hash) == true
    }

    if (!isCommitAlreadyVisible) {
        if (index >= 0) verticalScrollState.scrollToItem(index)
    }
}

suspend fun scrollToUncommittedChanges(
    verticalScrollState: LazyListState,
    commitList: GraphCommits,
) {
    if (commitList.isNotEmpty())
        verticalScrollState.scrollToItem(0)
}

@Composable
fun SearchFilter(
    logViewModel: RepositoryOpenViewModel,
    searchFilterResults: LogSearch.SearchResults,
    searchFocused: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var searchFilterText by remember { mutableStateOf(logViewModel.savedSearchFilter) }
    val textFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        textFieldFocusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = searchFilterText,
            onValueChange = {
                searchFilterText = it
                scope.launch {
                    logViewModel.onSearchValueChanged(it)
                }
            },
            maxLines = 1,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(textFieldFocusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        searchFocused()
                    }
                }
                .onPreviewKeyEvent { keyEvent ->
                    when {
                        keyEvent.matchesBinding(KeybindingOption.SIMPLE_ACCEPT) -> {
                            scope.launch {
                                logViewModel.selectNextFilterCommit()
                            }
                            true
                        }

                        keyEvent.matchesBinding(KeybindingOption.SEARCH_PREVIOUS_RESULT) -> {
                            scope.launch {
                                logViewModel.selectPreviousFilterCommit()
                            }
                            true
                        }

                        else -> false
                    }
                },
            label = {
                Text("Search by message, author name or commit ID")
            },
            colors = textFieldColors(),
            textStyle = MaterialTheme.typography.body1,
            trailingIcon = {
                Row(
                    modifier = Modifier
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (searchFilterText.isNotEmpty()) {
                        Text(
                            "${searchFilterResults.index}/${searchFilterResults.totalCount}",
                            color = MaterialTheme.colors.onBackgroundSecondary,
                        )
                    }

                    IconButton(
                        modifier = Modifier
                            .handOnHover(),
                        onClick = {
                            scope.launch { logViewModel.selectPreviousFilterCommit() }
                        }
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                    }

                    IconButton(
                        modifier = Modifier
                            .handOnHover(),
                        onClick = {
                            scope.launch { logViewModel.selectNextFilterCommit() }
                        }
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }

                    IconButton(
                        modifier = Modifier
                            .handOnHover()
                            .padding(end = 4.dp),
                        onClick = { logViewModel.closeSearch() }
                    ) {
                        Icon(painterResource(Res.drawable.close), contentDescription = null)
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CommitsList(
    scrollState: LazyListState,
    hasUncommittedChanges: Boolean,
    searchFilter: List<GraphCommit>?,
    focusedSearchMatch: String?,
    selectedCommit: Commit?,
    logState: LogState,
    repositoryState: RepositoryState,
    selectedItem: SelectedItem,
    commitList: GraphCommits,
    branches: Map<String, List<Branch>>,
    tags: Map<String, List<Tag>>,
    stashes: HashSet<String>,
    onAction: (LogAction) -> Unit,
    onMoveSelection: (LogMove, pageSize: Int, hasUncommittedRow: Boolean) -> Int?,
    onExtendSelection: (step: Int, hasUncommittedRow: Boolean) -> Int?,
    onCreateBranch: (Commit) -> Unit,
    onResetBranch: (Commit) -> Unit,
    onCreateTag: (Commit) -> Unit,
    onChangeUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onDeleteTag: (Tag) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    graphWidth: Dp,
    columnsLayout: LogColumnsLayout,
    horizontalScrollState: ScrollState,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var hasToScrollIfUncommittedChangesAppear by remember { mutableStateOf(false) }
    val headHash = remember(logState.currentBranch) { headCommitHash(logState.currentBranch) }
    // Fork-only: ⌘-click (Ctrl-click elsewhere) compares a row with the selection, and A and B are marked
    val comparison = LocalLogComparison.current
    val windowInfo = LocalWindowInfo.current
    val isCompareClick = {
        // Read at the click, so that pressing a modifier recomposes nothing
        val modifiers = windowInfo.keyboardModifiers
        isCompareClick(modifiers.isMetaPressed, modifiers.isCtrlPressed)
    }
    // Fork-only: Shift-click and Shift+arrows select a range of rows (docs/fork/squash-and-rebase.md, A2)
    val selectedCommits = LocalSelectedCommits.current
    val commitRewrite = LocalCommitRewrite.current
    val isRangeClick = { windowInfo.keyboardModifiers.isShiftPressed }

    val firstCommitKey = commitList.commits.keys.firstOrNull().orEmpty()
    // The first commit when the log was last shown, kept for the tab. Showing the log again, such as after closing a
    // diff, must leave it where it was; only a new commit at the top scrolls it there.
    val lastFirstCommitKey = rememberInTab("logLastFirstCommitKey") { mutableStateOf<String?>(null) }

    LaunchedEffect(firstCommitKey) {
        val previousFirstCommitKey = lastFirstCommitKey.value
        lastFirstCommitKey.value = firstCommitKey

        if (
            previousFirstCommitKey != null &&
            previousFirstCommitKey != firstCommitKey &&
            (commitList.commits.isNotEmpty() || hasToScrollIfUncommittedChangesAppear)
        ) {
            scrollState.scrollToItem(0)
        }
    }

    val isOnTop by remember(scrollState) {
        derivedStateOf {
            scrollState.firstVisibleItemIndex == 0
        }
    }


    LaunchedEffect(isOnTop, hasUncommittedChanges) {
        if (hasToScrollIfUncommittedChangesAppear && hasUncommittedChanges) {
            scrollState.scrollToItem(0)
        }

        hasToScrollIfUncommittedChangesAppear = if (isOnTop && !hasUncommittedChanges) {
            true
        } else {
            false
        }
    }

    val showsUncommittedRow = hasUncommittedChanges ||
        repositoryState.isMerging ||
        repositoryState.isRebasing ||
        repositoryState.isCherryPicking

    // Fork-only: the list takes the focus when a row is clicked, and the arrow, page, Home and End keys move the
    // selection (the commit pane follows it). Left and Right do nothing. The tab's own shortcuts still reach their
    // handler above, as the arrows aren't theirs.
    val ownFocusRequester = remember { FocusRequester() }
    val listFocusRequester = LocalLogFocusRequester.current ?: ownFocusRequester

    // Fork-only: a diff opening under the log makes it shorter. The selected row stays in view if it was in view.
    val selectedRow = remember(selectedCommit?.hash, commitList, showsUncommittedRow, selectedItem is SelectedItem.UncommittedChanges) {
        val offset = if (showsUncommittedRow) 1 else 0

        when {
            selectedCommit != null -> commitList.commits.keys.indexOf(selectedCommit.hash).takeIf { it >= 0 }?.plus(offset)
            selectedItem is SelectedItem.UncommittedChanges && showsUncommittedRow -> 0
            else -> null
        }
    }

    LaunchedEffect(scrollState, selectedRow) {
        if (selectedRow == null) return@LaunchedEffect

        var before: Pair<Int, Boolean>? = null

        snapshotFlow {
            val info = scrollState.layoutInfo

            (info.viewportEndOffset - info.viewportStartOffset) to info.visibleItemsInfo.any { it.index == selectedRow }
        }.collect { now ->
            val was = before
            before = now

            if (was != null && was.second && !now.second && now.first < was.first) {
                scrollState.bringRowIntoView(selectedRow)
            }
        }
    }

    ScrollableLazyColumn(
        state = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(listFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val step = keyEvent.logSelectionStep()

                if (step != null) {
                    val row = onExtendSelection(step, showsUncommittedRow)

                    if (row != null) scope.launch { scrollState.bringRowIntoView(row) }

                    return@onPreviewKeyEvent true
                }

                val move = keyEvent.logMove() ?: return@onPreviewKeyEvent false

                val row = onMoveSelection(move, scrollState.pageRows(), showsUncommittedRow)

                if (row != null) scope.launch { scrollState.bringRowIntoView(row) }

                // Consumed also at the ends, so that a key that can't move doesn't scroll the list under the selection
                true
            }
            // The underlying composable assigned to the horizontal scroll bar won't be receiving the scroll events
            // because the commits list will consume the events, so this code tries to scroll manually when it detects
            // horizontal scrolling
            .onPointerEvent(PointerEventType.Scroll) { pointerEvent ->
                scope.launch {
                    val xScroll = pointerEvent.changes.map { it.scrollDelta.x }.sum()
                    horizontalScrollState.scrollBy(xScroll * HORIZONTAL_SCROLL_PIXELS_MULTIPLIER)
                }
            },
    ) {
        if (showsUncommittedRow) {
            item {
                Box(
                    modifier = Modifier.height(MaterialTheme.linesHeight.logCommitHeight)
                        .clipToBounds()
                        .fillMaxWidth()
                        .handMouseClickable {
                            listFocusRequester.requestFocus()
                            onAction(uncommittedRowClick(isCompareClick()))
                        }
                ) {
                    UncommittedChangesGraphNode(
                        hasPreviousCommits = commitList.commits.isNotEmpty(),
                        // Fork-only: as B of a comparison with the working tree too
                        isSelected = selectedItem is SelectedItem.UncommittedChanges || comparison.workingTreeIsB,
                        modifier = Modifier.offset { IntOffset(-horizontalScrollState.value, 0) }
                    )

                    UncommittedChangesLine(
                        graphWidth = graphWidth,
                        isSelected = selectedItem == SelectedItem.UncommittedChanges || comparison.workingTreeIsB,
                        statusSummary = logState.statusSummary,
                        repositoryState = repositoryState,
                        compareLetter = CompareLetter.B.takeIf { comparison.workingTreeIsB },
                    )
                }
            }
        }

        // Setting a key makes the graph preserve the scroll position when a new line has been added on top (uncommitted changes)
        // Therefore, after popping a stash, the uncommitted changes wouldn't be visible and requires the user scrolling.
        // TODO This should be improved in case it's a dangling branch, shouldn't happen often but could be a thing
        items(
            items = commitList.values.toList(),
            key = { commit ->
                commit.hash + commit.lane + branches[commit.hash].orEmpty().joinToString() + tags[commit.hash].orEmpty()
                    .joinToString()
            },
        )
        { graphNode ->
            val isStash = stashes.contains(graphNode.hash)
            val compareLetter = comparison.letterOf(graphNode.hash)
            CommitLine(
                graphWidth = graphWidth,
                columnsLayout = columnsLayout,
                graphNode = graphNode,
                isSelected = selectedCommit?.hash == graphNode.hash || compareLetter != null ||
                    graphNode.hash in selectedCommits,
                selectedCommitsMenu = if (graphNode.hash in selectedCommits) {
                    SelectedCommitsMenu(
                        count = selectedCommits.size,
                        onCopyHashes = {
                            val hashes = commitList.commits.keys.filter { it in selectedCommits }

                            scope.launch { clipboard.setClipboardText(hashes.joinToString("\n")) }
                        },
                        onClearSelection = { onAction(LogAction.LogSelectionCleared) },
                        squash = commitRewrite?.let { rewrite -> { rewrite.rangeItem(selectedCommits) } },
                        edit = commitRewrite?.let { rewrite -> { rewrite.rangeEditorItem(selectedCommits) } },
                    )
                } else {
                    null
                },
                rewriteItems = { commitRewrite?.commitItems(graphNode.hash).orEmpty() },
                compareLetter = compareLetter,
                compareSelectedName = comparison.selectedName?.takeIf { selectedCommit?.hash != graphNode.hash },
                compareCurrentBranch = logState.currentBranch?.takeIf { it.hash != graphNode.hash },
                onCompareWithSelected = { onAction(LogAction.CompareWithSelected(graphNode.commit)) },
                onCompareWithCurrent = { onAction(LogAction.CompareWithCurrent(graphNode.commit)) },
                onCompareWith = { onAction(LogAction.CompareWith(graphNode.commit)) },
                onCompareWithWorkingTree = { onAction(LogAction.CompareWithWorkingTree(graphNode.commit)) },
                isHead = graphNode.hash == headHash,
                showInAmend = logState.currentBranch?.hash == graphNode.hash && !hasUncommittedChanges,
                isStash = isStash,
                branches = branches[graphNode.hash].orEmpty(),
                tags = tags[graphNode.hash].orEmpty(),
                currentBranch = logState.currentBranch,
                matchesSearchFilter = searchFilter?.contains(graphNode),
                isFocusedSearchMatch = focusedSearchMatch == graphNode.hash,
                horizontalScrollState = horizontalScrollState,
                showCreateNewBranch = { onCreateBranch(graphNode.commit) },
                showCreateNewTag = { onCreateTag(graphNode.commit) },
                resetBranch = { onResetBranch(graphNode.commit) },
                onMergeBranch = { onAction(LogAction.Merge(it)) },
                onDeleteBranch = { onDeleteBranch(it) },
                onDeleteRemoteBranch = { branch ->
                    onConfirmAction(ConfirmableAction.DeleteRemoteBranch(branch)) {
                        onAction(LogAction.DeleteRemoteBranch(branch))
                    }
                },
                onCheckoutTag = { onAction(LogAction.CheckoutTag(it)) },
                onDeleteTag = { onDeleteTag(it) },
                onPushToRemoteBranch = { onAction(LogAction.PushToRemoteBranch(it)) },
                onPullFromRemoteBranch = { onAction(LogAction.PullFromRemoteBranch(it)) },
                onRebaseBranch = { onAction(LogAction.Rebase(it)) },
                onRevCommitSelected = {
                    listFocusRequester.requestFocus()
                    onAction(commitRowClick(graphNode.commit, isStash, isCompareClick(), isRangeClick()))
                },
                onChangeDefaultUpstreamBranch = { onChangeUpstreamBranch(it) },
                onRenameBranch = { onRenameBranch(it) },
                onDeleteStash = {
                    onConfirmAction(ConfirmableAction.DropStash(graphNode.commit)) {
                        onAction(LogAction.DeleteStash(graphNode.commit))
                    }
                },
                onApplyStash = { onAction(LogAction.ApplyStash(graphNode.commit)) },
                onPopStash = { onAction(LogAction.PopStash(graphNode.commit)) },
                onShowStatusAmending = { onAction(LogAction.ShowStatusAmending) },
                onCheckoutCommit = { onAction(LogAction.CheckoutCommit(graphNode.commit)) },
                onRevertCommit = { onAction(LogAction.RevertCommit(graphNode.commit)) },
                onCherryPickCommit = { onAction(LogAction.CherryPickCommit(graphNode.commit)) },
                onCheckoutRemoteBranch = { onAction(LogAction.CheckoutRemoteBranch(it)) },
                onCheckoutBranch = { onAction(LogAction.CheckoutBranch(it)) },
                onCopyBranchNameToClipboard = {
                    scope.launch {
                        clipboard.setClipboardText(it.simpleName)
                    }
                },
                onCopyCommitHash = {
                    scope.launch {
                        clipboard.setClipboardText(graphNode.hash)
                    }
                },
            )
        }

        item {
            Box(modifier = Modifier.height(LOG_BOTTOM_PADDING.dp))
        }
    }
}


@Composable
fun GraphHeader(
    graphWidth: Dp,
    columns: List<LogColumnEntry>,
    columnsMenuItems: List<SortMenuItem>,
    onGraphDrag: (Float) -> Unit,
    onGraphDragStopped: () -> Unit,
    onColumnResize: (LogColumn, delta: Float) -> Unit,
    onColumnResizeFinished: (LogColumn) -> Unit,
    onShowSearch: () -> Unit,
) {
    LogColumnsHeaderMenu(columnsMenuItems) {
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(MaterialTheme.colors.tertiarySurface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .width(graphWidth)
                    .padding(start = 16.dp),
                text = "Graph",
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
            )

            val density = LocalDensity.current.density

            SimpleDividerLog(
                modifier = Modifier.draggable(
                    rememberDraggableState {
                        onGraphDrag(it / density) // Divide by density for screens with scaling > 1
                    },
                    Orientation.Horizontal,
                    onDragStopped = { onGraphDragStopped() },
                ),
            )

            Text(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
                text = "Message",
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
            )

            LogColumnsMenuButton(columnsMenuItems)

            IconButton(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .handOnHover(),
                onClick = onShowSearch
            ) {
                Icon(
                    painterResource(Res.drawable.search),
                    modifier = Modifier.size(18.dp),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackground,
                )
            }

            LogColumnHeaders(columns, onColumnResize, onColumnResizeFinished)

            Spacer(Modifier.width(LOG_ROW_END_PADDING))
        }
    }
    }
}

@Composable
fun UncommittedChangesLine(
    graphWidth: Dp,
    isSelected: Boolean,
    repositoryState: RepositoryState,
    statusSummary: StatusSummary,
    // Fork-only: B, while the comparison's B is the working tree
    compareLetter: CompareLetter? = null,
) {
    Row(
        modifier = Modifier
            .height(MaterialTheme.linesHeight.logCommitHeight)
            .padding(start = graphWidth)
            .backgroundIf(isSelected, MaterialTheme.colors.backgroundSelected)
            .padding(horizontal = DIVIDER_WIDTH.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = when {
            repositoryState.isRebasing -> "Pending changes to rebase"
            repositoryState.isMerging -> "Pending changes to merge"
            repositoryState.isCherryPicking -> "Pending changes to cherry-pick"
            repositoryState.isReverting -> "Pending changes to revert"
            else -> "Uncommitted changes"
        }

        if (compareLetter != null) {
            CompareRowBadge(compareLetter)
        }

        Text(
            text = text,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(start = if (compareLetter != null) 2.dp else 16.dp),
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            color = MaterialTheme.colors.onBackground,
        )

        Spacer(modifier = Modifier.weight(1f))

        LogStatusSummary(
            statusSummary = statusSummary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
fun LogStatusSummary(statusSummary: StatusSummary, modifier: Modifier) {
    Row(
        modifier = modifier,
    ) {
        if (statusSummary.modifiedCount > 0) {
            SummaryEntry(
                count = statusSummary.modifiedCount,
                icon = Icons.Default.Edit,
                color = MaterialTheme.colors.modifyFile,
            )
        }

        if (statusSummary.addedCount > 0) {
            SummaryEntry(
                count = statusSummary.addedCount,
                icon = Icons.Default.Add,
                color = MaterialTheme.colors.addFile,
            )
        }

        if (statusSummary.deletedCount > 0) {
            SummaryEntry(
                count = statusSummary.deletedCount,
                icon = Icons.Default.Delete,
                color = MaterialTheme.colors.deleteFile,
            )
        }

        if (statusSummary.conflictingCount > 0) {
            SummaryEntry(
                count = statusSummary.conflictingCount,
                icon = Icons.Default.Warning,
                color = MaterialTheme.styleColors.warningIcon,
            )
        }
    }
}

@Composable
fun SummaryEntry(
    count: Int, icon: ImageVector, color: Color,
) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
        )

        Icon(
            imageVector = icon, tint = color, contentDescription = null, modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun CommitLine(
    graphWidth: Dp,
    columnsLayout: LogColumnsLayout,
    graphNode: GraphCommit,
    isSelected: Boolean,
    // Fork-only: the menu of the commits selected with Shift, when the row is one of them
    selectedCommitsMenu: SelectedCommitsMenu?,
    // Fork-only: rewording the commit, and squashing or fixing it up into its parent
    rewriteItems: () -> List<ContextMenuElement>,
    // Fork-only: the side of a comparison that the row is, and what its menu's comparison items name
    compareLetter: CompareLetter?,
    compareSelectedName: String?,
    compareCurrentBranch: Branch?,
    onCompareWithSelected: () -> Unit,
    onCompareWithCurrent: () -> Unit,
    onCompareWith: () -> Unit,
    onCompareWithWorkingTree: () -> Unit,
    isHead: Boolean,
    currentBranch: Branch?,
    isStash: Boolean,
    showInAmend: Boolean,
    matchesSearchFilter: Boolean?,
    isFocusedSearchMatch: Boolean,
    showCreateNewBranch: () -> Unit,
    showCreateNewTag: () -> Unit,
    resetBranch: () -> Unit,
    onApplyStash: () -> Unit,
    onPopStash: () -> Unit,
    onDeleteStash: () -> Unit,
    onMergeBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onDeleteRemoteBranch: (Branch) -> Unit,
    onCheckoutTag: (Tag) -> Unit,
    onDeleteTag: (Tag) -> Unit,
    onPushToRemoteBranch: (Branch) -> Unit,
    onPullFromRemoteBranch: (Branch) -> Unit,
    onRebaseBranch: (Branch) -> Unit,
    onRevCommitSelected: () -> Unit,
    onShowStatusAmending: () -> Unit,
    onCheckoutCommit: () -> Unit,
    onRevertCommit: () -> Unit,
    onCherryPickCommit: () -> Unit,
    onCheckoutRemoteBranch: (Branch) -> Unit,
    onCheckoutBranch: (Branch) -> Unit,
    onChangeDefaultUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onCopyBranchNameToClipboard: (Branch) -> Unit,
    onCopyCommitHash: () -> Unit,
    horizontalScrollState: ScrollState,
    branches: List<Branch>,
    tags: List<Tag>,
) {
    val onNewWorktree = LocalOnNewWorktree.current

    ContextMenu(
        items = {
            if (selectedCommitsMenu != null) {
                selectedCommitsContextMenuItems(
                    count = selectedCommitsMenu.count,
                    onCopyHashes = selectedCommitsMenu.onCopyHashes,
                    onClearSelection = selectedCommitsMenu.onClearSelection,
                    squash = selectedCommitsMenu.squash?.invoke(),
                    edit = selectedCommitsMenu.edit?.invoke(),
                )
            } else if (isStash) {
                stashesContextMenuItems(
                    onApply = onApplyStash,
                    onPop = onPopStash,
                    onDelete = onDeleteStash,
                )
            } else {
                logContextMenu(
                    onShowStatusAmending = onShowStatusAmending,
                    onCheckoutCommit = onCheckoutCommit,
                    onCreateNewBranch = showCreateNewBranch,
                    onCreateNewTag = showCreateNewTag,
                    onRevertCommit = onRevertCommit,
                    onCherryPickCommit = onCherryPickCommit,
                    onCopyCommitHash = onCopyCommitHash,
                    onResetBranch = { resetBranch() },
                    showInAmend = showInAmend,
                    onNewWorktree = onNewWorktree?.let { { it(NewWorktreeStart.Commit(graphNode.commit)) } },
                    compare = CompareMenuItems(
                        selectedName = compareSelectedName,
                        onCompareWithSelected = onCompareWithSelected,
                        currentBranch = compareCurrentBranch,
                        onCompareWithCurrent = onCompareWithCurrent,
                        onCompareWith = onCompareWith,
                        onCompareWithWorkingTree = onCompareWithWorkingTree,
                    ),
                    rewriteItems = rewriteItems(),
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .height(MaterialTheme.linesHeight.logCommitHeight)
                .handMouseClickable { onRevCommitSelected() }
        ) {
            // Fork-only: the lanes come from the style
            val colors = MaterialTheme.graphLanes
            val nodeColor = colors[graphNode.lane % colors.size]

            Box {
                Row(
                    modifier = Modifier
                        .clipToBounds()
                        .fillMaxHeight()
                        .fillMaxWidth()
                        // In pixels, as the scroll state's value is: as dp, the graph moved twice as far as the
                        // scroll bar on a 2x screen
                        .offset { IntOffset(-horizontalScrollState.value, 0) }
                ) {
                    CommitsGraph(
                        modifier = Modifier
                            .fillMaxHeight(),
                        plotCommit = graphNode,
                        isStash = isStash,
                        nodeColor = nodeColor,
                        isSelected = isSelected,
                        isHead = isHead,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(start = graphWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colors.background)
                    .backgroundIf(isSelected, MaterialTheme.colors.backgroundSelected)
                    .outlinedWhenFocusedMatch(isFocusedSearchMatch)
            ) {

                if (matchesSearchFilter == true) {
                    Box(
                        modifier = Modifier
                            .padding(start = DIVIDER_WIDTH.dp)
                            .background(MaterialTheme.colors.secondary)
                            .fillMaxHeight()
                            .width(4.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = LOG_ROW_END_PADDING)
                        .dimmedWhenNotMatching(matchesSearchFilter),
                ) {
                    CommitMessage(
                        graphCommit = graphNode,
                        isHead = isHead,
                        compareLetter = compareLetter,
                        columnsLayout = columnsLayout,
                        nodeColor = nodeColor,
                        currentBranch = currentBranch,
                        isStash = isStash,
                        branches = branches,
                        tags = tags,
                        onCheckoutBranch = { ref ->
                            if (ref.isRemote) {
                                onCheckoutRemoteBranch(ref)
                            } else {
                                onCheckoutBranch(ref)
                            }
                        },
                        onMergeBranch = onMergeBranch,
                        onDeleteBranch = onDeleteBranch,
                        onDeleteRemoteBranch = onDeleteRemoteBranch,
                        onCheckoutTag = onCheckoutTag,
                        onDeleteTag = onDeleteTag,
                        onRebaseBranch = onRebaseBranch,
                        onPushRemoteBranch = onPushToRemoteBranch,
                        onPullRemoteBranch = onPullFromRemoteBranch,
                        onChangeDefaultUpstreamBranch = onChangeDefaultUpstreamBranch,
                        onRenameBranch = onRenameBranch,
                        onCopyBranchNameToClipboard = onCopyBranchNameToClipboard,
                    )
                }
            }
        }
    }
}

@Composable
fun CommitMessage(
    graphCommit: GraphCommit,
    columnsLayout: LogColumnsLayout,
    currentBranch: Branch?,
    isStash: Boolean,
    tags: List<Tag>,
    branches: List<Branch>,
    nodeColor: Color,
    isHead: Boolean = false,
    // Fork-only: the side of a comparison that the row is, which a badge before the chips names
    compareLetter: CompareLetter? = null,
    onCheckoutBranch: (ref: Branch) -> Unit,
    onMergeBranch: (ref: Branch) -> Unit,
    onDeleteBranch: (ref: Branch) -> Unit,
    onDeleteRemoteBranch: (ref: Branch) -> Unit,
    onRebaseBranch: (ref: Branch) -> Unit,
    onCheckoutTag: (tag: Tag) -> Unit,
    onDeleteTag: (tag: Tag) -> Unit,
    onPushRemoteBranch: (ref: Branch) -> Unit,
    onPullRemoteBranch: (ref: Branch) -> Unit,
    onChangeDefaultUpstreamBranch: (ref: Branch) -> Unit,
    onRenameBranch: (ref: Branch) -> Unit,
    onCopyBranchNameToClipboard: (ref: Branch) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize()
            .hoverable(
                // This modifier is added just to prevent committer tooltip is shown then it is underneath this message
                remember { MutableInteractionSource() },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The Message column (fork-only): the chips take at most part of it, and the columns after it keep their widths
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Fork-only: the badge of a comparison's side takes the place of the chips' start padding
            val chipsStart = if (compareLetter != null) {
                CompareRowBadge(compareLetter)
                0.dp
            } else {
                REF_CHIPS_START_PADDING
            }

            if (isStash) {
                Spacer(modifier = Modifier.width(chipsStart))
            } else {
                LogRefChips(
                    startPadding = chipsStart,
                    branches = branches,
                    tags = tags,
                    currentBranch = currentBranch,
                    color = nodeColor,
                    actions = LogRefActions(
                        onCheckoutBranch = onCheckoutBranch,
                        onMergeBranch = onMergeBranch,
                        onDeleteBranch = onDeleteBranch,
                        onDeleteRemoteBranch = onDeleteRemoteBranch,
                        onRebaseBranch = onRebaseBranch,
                        onCheckoutTag = onCheckoutTag,
                        onDeleteTag = onDeleteTag,
                        onPushRemoteBranch = onPushRemoteBranch,
                        onPullRemoteBranch = onPullRemoteBranch,
                        onChangeDefaultUpstreamBranch = onChangeDefaultUpstreamBranch,
                        onRenameBranch = onRenameBranch,
                        onCopyBranchNameToClipboard = onCopyBranchNameToClipboard,
                    ),
                )
            }

            val message = remember(graphCommit.hash) {
                graphCommit.commit.shortMessage
            }

            Text(
                text = message,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
                style = MaterialTheme.typography.body2.boldWhenHead(isHead),
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LogColumnCells(
            graphCommit = graphCommit,
            layout = columnsLayout,
        )
    }
}

@Composable
fun DividerLog(modifier: Modifier, graphWidth: Dp) {
    Box(
        modifier = Modifier
            .padding(start = graphWidth)
            .width(DIVIDER_WIDTH.dp)
            .then(modifier)
            .pointerHoverIcon(resizePointerIconEast)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(color = MaterialTheme.colors.onBackground.copy(alpha = 0.2F))
                .align(Alignment.Center)
        )
    }
}

@Composable
fun SimpleDividerLog(modifier: Modifier) {
    DividerLog(modifier, graphWidth = 0.dp)
}


@Composable
fun CommitsGraph(
    modifier: Modifier = Modifier,
    plotCommit: GraphCommit,
    isStash: Boolean,
    nodeColor: Color,
    isSelected: Boolean,
    isHead: Boolean = false,
) {
    val colors = MaterialTheme.graphLanes
    val passingLanes = plotCommit.passingLanes
    val forkingOffLanes = plotCommit.forkingOffLanes
    val mergingLanes = plotCommit.mergingLanes
    val density = LocalDensity.current.density
    val laneWidthWithDensity = remember(density) {
        LANE_WIDTH * density
    }

    Box(
        modifier = modifier
            .backgroundIf(isSelected, MaterialTheme.colors.backgroundSelected)
            .fillMaxHeight(),
        contentAlignment = Alignment.CenterStart,
    ) {

        val itemPosition = plotCommit.lane
        val arcsAngleMultiplier = 0.50F

        val hasMergeAndForkOff = forkingOffLanes.isNotEmpty() && mergingLanes.isNotEmpty()

        val laneHeightModifier = if (hasMergeAndForkOff) {
            2f
        } else {
            0f
        }

        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            clipRect {
                if (plotCommit.childCount > 0) {
                    drawLine(
                        color = colors[itemPosition % colors.size],
                        start = Offset(laneWidthWithDensity * (itemPosition + 1), this.center.y),
                        end = Offset(laneWidthWithDensity * (itemPosition + 1), 0f),
                        strokeWidth = 2f * density,
                    )
                }

                forkingOffLanes.forEach { plotLane ->

                    val x1 = laneWidthWithDensity * (itemPosition + 1)
                    val x2 = laneWidthWithDensity * (plotLane + 1) - (laneWidthWithDensity * arcsAngleMultiplier)
                    val x3 = laneWidthWithDensity * (plotLane + 1)
                    val y1 = this@clipRect.center.y - (laneHeightModifier * density)
                    val y2 = this@clipRect.center.y - (laneWidthWithDensity * arcsAngleMultiplier)
                    val y3 = 0F
                    val startAngle = 90F
                    val sweepAngleDegrees = -90F

                    val arcRect = Rect(x2, y2, x3, y1)

                    graphArc(x1, y1, x2, arcRect, startAngle, sweepAngleDegrees, x3, y3, plotLane, density, colors)
                }

                mergingLanes.forEach { plotLane ->
                    val direction = if (plotLane < itemPosition) {
                        ARC_DIRECTION_LEFT
                    } else {
                        ARC_DIRECTION_RIGHT
                    }

                    val x1 = laneWidthWithDensity * (itemPosition + 1)
                    val x2 =
                        laneWidthWithDensity * (plotLane + 1) + (laneWidthWithDensity * arcsAngleMultiplier * direction * -1)
                    val x3 = laneWidthWithDensity * (plotLane + 1)
                    val y1 = this@clipRect.center.y + (laneHeightModifier * density)
                    val y2 = this@clipRect.center.y + (laneWidthWithDensity * arcsAngleMultiplier)
                    val y3 = this@clipRect.size.height
                    val startAngle = 270F
                    val sweepAngleDegrees = 90F * direction

                    val arcRect = if (plotLane < itemPosition) {
                        Rect(x3, y1, x2, y2)
                    } else {
                        Rect(x2, y1, x3, y2)
                    }

                    graphArc(x1, y1, x2, arcRect, startAngle, sweepAngleDegrees, x3, y3, plotLane, density, colors)
                }

                if (plotCommit.commit.parentCount > 0) {
                    drawLine(
                        color = colors[itemPosition % colors.size],
                        start = Offset(laneWidthWithDensity * (itemPosition + 1), this.center.y),
                        end = Offset(laneWidthWithDensity * (itemPosition + 1), this.size.height),
                        strokeWidth = 2f * density,
                    )
                }

                passingLanes.forEach { plotLane ->
                    drawLine(
                        color = colors[plotLane % colors.size],
                        start = Offset(laneWidthWithDensity * (plotLane + 1), 0f),
                        end = Offset(laneWidthWithDensity * (plotLane + 1), this.size.height),
                        strokeWidth = 2f * density,
                    )
                }
            }
        }

        CommitNode(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = (LANE_WIDTH * itemPosition + LANE_WIDTH / 2).dp)
                .fillMaxHeight()
                .width(LANE_WIDTH.dp),
            author = plotCommit.author,
            isMerge = plotCommit.commit.parentCount > 1,
            isStash = isStash,
            color = nodeColor,
            isHead = isHead,
        )
    }
}

private fun DrawScope.graphArc(
    x1: Float,
    y1: Float,
    x2: Float,
    arcRect: Rect,
    startAngle: Float,
    sweepAngleDegrees: Float,
    x3: Float,
    y3: Float,
    plotLane: Int,
    density: Float,
    colors: List<Color>,
) {
    val path = Path().apply {
        moveTo(x1, y1)
        lineTo(x2, y1)
        arcTo(
            rect = arcRect,
            startAngleDegrees = startAngle,
            sweepAngleDegrees = sweepAngleDegrees,
            forceMoveTo = false
        )
        lineTo(x3, y3)
    }

    drawPath(
        path = path,
        color = colors[plotLane % colors.size],
        style = Stroke(width = 2f * density)
    )
}

/** A dot centered in [modifier]'s box. Hovering it shows the author with their avatar. */
@Composable
fun CommitNode(
    modifier: Modifier = Modifier,
    author: Identity,
    isMerge: Boolean,
    isStash: Boolean,
    color: Color,
    isHead: Boolean = false,
) {
    if (isStash) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(COMMIT_NODE_SIZE)
                    .border(2.dp, color, shape = CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.background),
            )
        }
    } else {
        val shape = if (isMerge) {
            RoundedCornerShape(2.dp)
        } else {
            CircleShape
        }

        InstantTooltip(
            text = "${author.name} <${author.email}>",
            leadingContent = {
                AvatarImage(
                    modifier = Modifier.size(COMMIT_TOOLTIP_AVATAR_SIZE),
                    personIdent = author,
                )
            },
            modifier = modifier,
            position = InstantTooltipPosition.RIGHT,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isHead) HeadRing()

                Box(
                    modifier = Modifier
                        .size(COMMIT_NODE_SIZE)
                        .clip(shape)
                        .background(color),
                )
            }
        }
    }
}

@Composable
fun UncommittedChangesGraphNode(
    modifier: Modifier = Modifier,
    hasPreviousCommits: Boolean,
    isSelected: Boolean,
) {
    val density = LocalDensity.current.density
    val colors = MaterialTheme.graphLanes

    val laneWidthWithDensity = remember(density) {
        LANE_WIDTH * density
    }
    Box(
        modifier = modifier
            .backgroundIf(isSelected, MaterialTheme.colors.backgroundSelected)
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            clipRect {
                if (hasPreviousCommits) drawLine(
                    color = colors[0],
                    start = Offset(laneWidthWithDensity, this.center.y),
                    end = Offset(laneWidthWithDensity, this.size.height),
                    strokeWidth = 2f * density,
                )

                drawCircle(
                    color = colors[0],
                    radius = COMMIT_NODE_SIZE.toPx() / 2,
                    center = Offset(laneWidthWithDensity, this.center.y),
                )
            }
        }
    }
}

@Composable
fun BranchChip(
    modifier: Modifier = Modifier,
    isCurrentBranch: Boolean = false,
    branch: Branch,
    currentBranch: Branch?,
    onCheckoutBranch: (Branch) -> Unit,
    onMergeBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onDeleteRemoteBranch: (Branch) -> Unit,
    onRebaseBranch: (Branch) -> Unit,
    onPushRemoteBranch: (Branch) -> Unit,
    onPullRemoteBranch: (Branch) -> Unit,
    onChangeDefaultUpstreamBranch: (Branch) -> Unit,
    onCopyBranchNameToClipboard: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    color: Color,
    // Fork-only: the remote branches of the same name that share this local branch's chip, and their menu items, in
    // place of upstream's trackingRemoteBranch
    sharedRemotes: List<Branch> = emptyList(),
    sharedRemotesMenuItems: () -> List<ContextMenuElement> = { emptyList() },
) {
    val branchWorktrees = LocalBranchWorktrees.current
    val worktreeUsers = branchWorktrees.usersOf(branch)
    val onChooseWorktreesBase = LocalOnChooseWorktreesBase.current
    val onSwitchToWorktree = LocalOnSwitchToWorktree.current
    val onNewWorktree = LocalOnNewWorktree.current
    val compareWithCurrent = LocalCompareWithCurrent.current
    val branchCherryPick = LocalBranchCherryPick.current
    val commitRewrite = LocalCommitRewrite.current

    val contextMenuItemsList = {
        branchContextMenuItems(
            branch = branch,
            currentBranch = currentBranch,
            isCurrentBranch = isCurrentBranch,
            isLocal = branch.isLocal,
            onCheckoutBranch = onCheckoutBranch,
            onMergeBranch = onMergeBranch,
            onDeleteBranch = onDeleteBranch,
            onDeleteRemoteBranch = onDeleteRemoteBranch,
            onRebaseBranch = onRebaseBranch,
            onPushToRemoteBranch = onPushRemoteBranch,
            onPullFromRemoteBranch = onPullRemoteBranch,
            onChangeDefaultUpstreamBranch = onChangeDefaultUpstreamBranch,
            onRenameBranch = onRenameBranch,
            onCopyBranchNameToClipboard = onCopyBranchNameToClipboard,
            worktreeUsers = worktreeUsers,
            worktreesBase = branchWorktrees.baseChoice,
            onChooseWorktreesBase = onChooseWorktreesBase,
            onSwitchToWorktree = onSwitchToWorktree,
            onNewWorktree = onNewWorktree?.let { onNew ->
                {
                    onNew(
                        if (branch.isLocal) NewWorktreeStart.LocalBranch(branch) else NewWorktreeStart.RemoteBranch(branch)
                    )
                }
            },
            onCompareWithCurrent = compareWithCurrent?.let { compare -> { compare.onBranch(branch) } },
            onCompareWith = compareWithCurrent?.onBranchWith?.let { compare -> { compare(branch) } },
            onCompareWithWorkingTree = compareWithCurrent?.onBranchWithWorkingTree?.let { compare -> { compare(branch) } },
            cherryPick = branchCherryPick?.itemFor(branch),
            onCherryPick = { item -> branchCherryPick?.onCherryPick?.invoke(branch, item) },
            squashBranch = commitRewrite?.takeIf { branch.isLocal }?.branchItem(branch.name),
            editBranch = commitRewrite?.takeIf { branch.isLocal }?.branchEditorItem(branch.name),
        ) + sharedRemotesMenuItems().let { items ->
            if (items.isEmpty()) items else listOf(ContextMenuElement.ContextSeparator) + items
        }
    }

    val endingContent: @Composable RowScope.() -> Unit = {
        if (sharedRemotes.isNotEmpty()) {
            SharedRemotesChipMark(remotes = sharedRemotes, modifier = Modifier.padding(end = 6.dp))
        }

        if (worktreeUsers != null) {
            BranchWorktreeChipMark(
                users = worktreeUsers,
                baseBranch = branchWorktrees.baseBranch,
                modifier = Modifier.padding(end = 6.dp),
            )
        }

        val sizeMultiplierCurrentBranch by animateFloatAsState(if (isCurrentBranch) 1f else 0f)

        Icon(
            painter = painterResource(Res.drawable.location),
            contentDescription = null,
            modifier = Modifier
                .size((18 * sizeMultiplierCurrentBranch).dp)
                .padding(end = (6 * sizeMultiplierCurrentBranch).dp),
            tint = MaterialTheme.colors.primaryVariant,
        )
    }

    Chip(
        modifier = modifier.draggable(
            rememberDraggableState {

            },
            orientation = Orientation.Vertical,
        ),
        color = color,
        // Fork-only: a remote branch's chip keeps its lane's colour, where upstream's is a fixed gold. Its cloud icon
        // says that it's remote.
        text = branch.logName,
        icon = if (branch.isLocal) Res.drawable.branch else Res.drawable.cloud,
        onCheckoutRef = { onCheckoutBranch(branch) },
        contextMenuItemsList = contextMenuItemsList,
        endingContent = endingContent,
    )
}

val Branch.logName: String
    get() = when {
        this.name == Constants.HEAD -> {
            this.name
        }

        this.isRemote -> {
            name.replace("refs/remotes/", "")
        }

        else -> {
            val split = this.name.split("/")
            split.takeLast(split.size - LOCAL_PREFIX_LENGTH).joinToString("/")
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagChip(
    modifier: Modifier = Modifier,
    tag: Tag,
    onCheckoutTag: () -> Unit,
    onDeleteTag: () -> Unit,
    color: Color,
) {
    val compareWithCurrent = LocalCompareWithCurrent.current
    val contextMenuItemsList = {
        tagContextMenuItems(
            onCheckoutTag = onCheckoutTag,
            onDeleteTag = onDeleteTag,
            currentBranch = compareWithCurrent?.currentBranch,
            onCompareWithCurrent = compareWithCurrent?.let { compare -> { compare.onTag(tag) } },
            onCompareWith = compareWithCurrent?.onTagWith?.let { compare -> { compare(tag) } },
            onCompareWithWorkingTree = compareWithCurrent?.onTagWithWorkingTree?.let { compare -> { compare(tag) } },
        )
    }

    Chip(
        modifier,
        tag.simpleName,
        Res.drawable.tag,
        onCheckoutRef = onCheckoutTag,
        contextMenuItemsList = contextMenuItemsList,
        color = color,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Chip(
    modifier: Modifier = Modifier,
    text: String,
    icon: DrawableResource,
    color: Color,
    iconBackgroundColor: Color = color,
    onCheckoutRef: () -> Unit,
    contextMenuItemsList: () -> List<ContextMenuElement>,
    endingContent: @Composable RowScope.() -> Unit = {},
) {
    // The first click of a double click selects the commit, which recomposes the line. A new lambda as key would
    // restart the double click detection and lose that first click.
    val currentOnCheckoutRef by rememberUpdatedState(onCheckoutRef)

    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(width = 2.dp, color = color, shape = RoundedCornerShape(16.dp))
            // Not clickable, so a single click reaches the commit line and selects the commit.
            .onDoubleClick { currentOnCheckoutRef() }
            .handOnHover()
    ) {
        ContextMenu(
            items = contextMenuItemsList
        ) {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.background(color = iconBackgroundColor)) {
                    Icon(
                        modifier = Modifier
                            .padding(MaterialTheme.linesHeight.refChipIconPadding)
                            .size(14.dp),
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colors.background,
                    )
                }
                // Fork-only: a chip narrower than its name (the log caps them) ends the name in "…", which its
                // tooltip has whole. The marks after it keep their room.
                var isCut by remember { mutableStateOf(false) }

                DelayedTooltip(
                    text = if (isCut) text else null,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { isCut = it.hasVisualOverflow },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }

                endingContent()
            }
        }
    }
}
/** The menu of the commits selected with Shift, which a row of them shows in place of its own (fork-only). */
private class SelectedCommitsMenu(
    val count: Int,
    val onCopyHashes: () -> Unit,
    val onClearSelection: () -> Unit,
    val squash: (() -> ContextMenuElement)? = null,
    val edit: (() -> ContextMenuElement?)? = null,
)
