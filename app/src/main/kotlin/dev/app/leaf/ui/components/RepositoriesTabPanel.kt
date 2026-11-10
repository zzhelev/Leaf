package dev.app.leaf.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.onDoubleClick
import dev.app.leaf.extensions.onMiddleMouseButtonClick
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.drag_sorting.HorizontalDraggableItem
import dev.app.leaf.ui.drag_sorting.horizontalDragContainer
import dev.app.leaf.ui.drag_sorting.rememberHorizontalDragDropState
import dev.app.leaf.theme.frameColors
import dev.app.leaf.ui.window.TITLE_BAR_TAB_GAP
import dev.app.leaf.ui.window.TITLE_BAR_TAB_HEIGHT
import dev.app.leaf.ui.window.TabScrollArrow
import dev.app.leaf.ui.window.TitleBarTab
import dev.app.leaf.ui.window.tabsFitTitleBar
import dev.app.leaf.ui.window.titleBarTabWidth
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T : TabInformationProvider> TabsRow(
    tabs: List<TabInformation<T>>,
    defaultTabName: String,
    currentTab: TabInformation<T>?,
    onTabSelected: (TabInformation<T>) -> Unit,
    onTabClosed: (TabInformation<T>) -> Unit,
    onMoveTab: (Int, Int) -> Unit,
    onAddNewTab: () -> Unit,
    // Fork-only: the tab whose name is being edited in place, and what Enter or Esc there does after (upstream issue 242)
    renamingTab: TabInformation<T>? = null,
    onRenamingTabChange: (TabInformation<T>?) -> Unit = {},
    onRenamedFromKeyboard: () -> Unit = {},
    // Fork-only (docs/fork/window-chrome.md): the room the tabs have in the window's title bar, null elsewhere. There,
    // tabs shrink before they scroll, and arrows take the place of the scrollbar, which the bar has no room for.
    titleBarWidth: Dp? = null,
) {
    val stateHorizontal = rememberLazyListState()
    val scrollAdapter = rememberScrollbarAdapter(stateHorizontal)

    val canBeScrolled by remember {
        derivedStateOf {
            val layoutInfo = stateHorizontal.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            // Before the first layout there are items but none visible yet
            if (layoutInfo.totalItemsCount == 0 || visibleItemsInfo.isEmpty()) {
                false
            } else {
                val firstVisibleItem = visibleItemsInfo.first()
                val lastVisibleItem = visibleItemsInfo.last()

                val viewportHeight = layoutInfo.viewportEndOffset + layoutInfo.viewportStartOffset

                !(firstVisibleItem.index == 0 &&
                        firstVisibleItem.offset == 0 &&
                        lastVisibleItem.index + 1 == layoutInfo.totalItemsCount &&
                        lastVisibleItem.offset + lastVisibleItem.size <= viewportHeight)
            }
        }
    }

    // Fork-only: the most each tab may take in the title bar, and whether they still don't fit
    val titleBarTabWidth = titleBarWidth?.let { titleBarTabWidth(it, tabs.size) }
    val showsScrollArrows = titleBarWidth != null && !tabsFitTitleBar(titleBarWidth, tabs.size)

    Column {
        if (canBeScrolled && titleBarWidth == null) {
            DelayedTooltip(
                "\"Shift + Mouse wheel\" to scroll"
            ) {
                HorizontalScrollbar(
                    modifier = Modifier
                        .fillMaxWidth(),
                    adapter = scrollAdapter
                )
            }
        }


        val dragDropState = rememberHorizontalDragDropState(stateHorizontal) { fromIndex, toIndex ->
            onMoveTab(fromIndex, toIndex)
        }

        // Fork-only (upstream issue 368): the selected tab shows whole, however it was selected: a click, the keys, a
        // tab added or one that a menu went to. It used to scroll only when a tab was added, and then to the end. A tab
        // selected by dragging it is left where the drag takes it.
        val selectedIndex = tabs.indexOf(currentTab)

        LaunchedEffect(currentTab, tabs.size) {
            if (selectedIndex < 0 || dragDropState.draggingItemIndex != null) return@LaunchedEffect

            // A tab just added isn't laid out yet
            snapshotFlow { stateHorizontal.layoutInfo.totalItemsCount }.first { it == tabs.size }
            stateHorizontal.revealItem(selectedIndex)
        }

        Row {
            if (showsScrollArrows) {
                TabScrollArrow(stateHorizontal, forward = false)
            }

            LazyRow(
                modifier = Modifier
                    .height(36.dp)
                    .weight(1f, false)
                    .horizontalDragContainer(
                        dragDropState = dragDropState,
                        onDraggedItem = {
                            val tab = tabs.getOrNull(it)

                            if (tab != null) {
                                onTabSelected(tab)
                            }
                        },
                    ),
                state = stateHorizontal,
                // Fork-only: the title bar's tabs are apart and centred in it
                horizontalArrangement = if (titleBarWidth != null) Arrangement.spacedBy(TITLE_BAR_TAB_GAP) else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(
                    items = tabs,
                    key = { _, tab -> tab.data }
                ) { index, tab ->
                    HorizontalDraggableItem(dragDropState, index) { _ ->
                        val tooltip by tab.extraInfo.collectAsState()
                        val tabName by tab.name.collectAsState()
                        val worktreeName by tab.worktreeName.collectAsState()
                        val look by tab.look.collectAsState()


                        InstantTooltip(tooltip) {
                            // Fork-only: rename the tab's repository, give it a colour (upstream issue 242)
                            ContextMenu(
                                items = {
                                    tabContextMenuItems(
                                        look = look,
                                        onRename = { onRenamingTabChange(tab) },
                                        onSetColor = { tab.data.setTabColor(it) },
                                        onResetName = { tab.data.resetTabName() },
                                        onClose = { onTabClosed(tab) },
                                    )
                                },
                            ) {
                                Tab(
                                    modifier = if (titleBarTabWidth != null) {
                                        Modifier.widthIn(max = titleBarTabWidth)
                                    } else {
                                        Modifier
                                    },
                                    title = tabName ?: defaultTabName,
                                    worktreeName = worktreeName,
                                    isSelected = currentTab == tab,
                                    onClick = {
                                        onTabSelected(tab)
                                    },
                                    onCloseTab = {
                                        onTabClosed(tab)
                                    },
                                    color = tabColor(look?.color),
                                    inTitleBar = titleBarWidth != null,
                                    hasRepository = look != null,
                                    isRenaming = renamingTab == tab,
                                    onDoubleClick = if (look != null) {
                                        { onRenamingTabChange(tab) }
                                    } else {
                                        null
                                    },
                                    onRenameDone = { typed, byKey ->
                                        onRenamingTabChange(null)

                                        if (typed != null) {
                                            tab.data.renameTab(typed)
                                        }

                                        if (byKey) {
                                            onRenamedFromKeyboard()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (showsScrollArrows) {
                TabScrollArrow(stateHorizontal, forward = true)
            }

            IconButton(
                onClick = {
                    onAddNewTab()
                },
                modifier = Modifier
                    .run { if (titleBarWidth != null) padding(start = TITLE_BAR_TAB_GAP).size(TITLE_BAR_TAB_HEIGHT) else size(36.dp) }
                    .handOnHover()
                    .align(Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = if (titleBarWidth != null) MaterialTheme.frameColors.secondaryText else MaterialTheme.colors.primaryVariant,
                )
            }
        }
    }
}

@Composable
fun Tab(
    modifier: Modifier,
    title: String,
    worktreeName: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onCloseTab: () -> Unit,
    // Fork-only (upstream issue 242): the colour of its stripe, and its name edited in place
    color: Color? = null,
    // Fork-only (docs/fork/window-chrome.md): a tab in the window's title bar, which has a look of its own
    inTitleBar: Boolean = false,
    hasRepository: Boolean = true,
    isRenaming: Boolean = false,
    onDoubleClick: (() -> Unit)? = null,
    onRenameDone: (typed: String?, byKey: Boolean) -> Unit = { _, _ -> },
) {
    if (inTitleBar) {
        TitleBarTab(
            modifier, title, worktreeName, isSelected, hasRepository, onClick, onCloseTab, color, isRenaming,
            onDoubleClick, onRenameDone,
        )

        return
    }

    val backgroundColor = if (isSelected)
        MaterialTheme.colors.surface
    else
        MaterialTheme.colors.background

    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .widthIn(min = 200.dp)
            .width(IntrinsicSize.Max)
            .fillMaxHeight()
            .hoverable(hoverInteraction)
            .handMouseClickable { onClick() }
            .run { if (onDoubleClick != null && !isRenaming) onDoubleClick(onDoubleClick) else this }
            .onMiddleMouseButtonClick {
                onCloseTab()
            }
            .background(backgroundColor),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Fork-only: a linked worktree's tab is named after its repository, then the worktree, lighter
            val worktreeColor = MaterialTheme.colors.onBackgroundSecondary
            val text = remember(title, worktreeName, worktreeColor) {
                buildAnnotatedString {
                    append(title)

                    if (worktreeName != null) {
                        withStyle(SpanStyle(color = worktreeColor, fontWeight = FontWeight.Normal)) {
                            append(" · $worktreeName")
                        }
                    }
                }
            }

            if (isRenaming) {
                // The field holds the whole title, as a renamed tab shows no worktree. Its border and padding take 7 dp,
                // so the name stays where it was.
                Box(
                    modifier = Modifier
                        .padding(start = 9.dp)
                        .weight(1f),
                ) {
                    TabNameField(name = title, onDone = onRenameDone)
                }
            } else {
                Text(
                    text = text,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f)
                        .widthIn(max = 720.dp),
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            IconButton(
                onClick = onCloseTab,
                enabled = isHovered || isSelected,
                modifier = Modifier
                    .alpha(if (isHovered || isSelected) 1f else 0f)
                    .padding(horizontal = 8.dp)
                    .size(14.dp)
            ) {
                Icon(
                    painterResource(Res.drawable.close),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackground
                )
            }
        }

        if (color != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(TAB_COLOR_STRIPE_HEIGHT)
                    .background(color)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colors.primaryVariant)
            )
        }
    }
}

