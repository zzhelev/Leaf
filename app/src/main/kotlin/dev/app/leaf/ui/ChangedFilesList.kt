// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.files_changed_column_directory
import dev.app.leaf.app.generated.resources.files_changed_column_file
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FileSortKey
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.SortSettingsCodec
import dev.app.leaf.extensions.backgroundIf
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.onDoubleClick
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.FolderEntryContent
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.StartEllipsisPathText
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.ContextMenuElement
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Width of the change icon with its padding, where the file name starts. */
private val ICON_AREA_WIDTH = 32.dp
private val COLUMN_GAP = 16.dp
private val ROW_END_PADDING = 16.dp
private val TREE_INDENT = 18.dp
private val TREE_FOLDER_START = 10.dp

/**
 * Changed files as a flat list, split columns or a folder tree, for the Files changed, Staged and Unstaged panes.
 * Up and Down move the selection; in the tree, Left and Right close and open folders or move to the parent or first
 * child.
 *
 * @param selectedEntries The selected files. The keyboard moves from the last one.
 * @param onKeyboardSelect Selects a file the keyboard moved to, [onFileClick] by default.
 * @param fileTrailingAction Drawn over the end of a file row, for example a button shown while it is hovered.
 */
@Composable
fun <T> ChangedFilesList(
    rows: List<FileRow<T>>,
    viewState: FilesViewState,
    selectedEntries: List<T>,
    listState: LazyListState,
    /** Resets the keyboard position on folders, for example when another commit is selected. */
    resetKey: Any?,
    fileIcon: (T) -> ImageVector,
    fileIconColor: @Composable (T) -> Color,
    onFileClick: (T) -> Unit,
    onFolderToggle: (path: String) -> Unit,
    onSplitRatioChange: (Float) -> Unit,
    onGenerateContextMenu: (T) -> List<ContextMenuElement>,
    modifier: Modifier = Modifier,
    onKeyboardSelect: (T) -> Unit = onFileClick,
    onFileDoubleClick: ((T) -> Unit)? = null,
    onGenerateFolderContextMenu: ((FileRow.Folder) -> List<ContextMenuElement>)? = null,
    fileTrailingAction: (@Composable BoxScope.(file: T, isHovered: Boolean) -> Unit)? = null,
    folderTrailingAction: (@Composable BoxScope.(folder: FileRow.Folder, isHovered: Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    // A folder the keyboard moved to. On files, the selection is the position.
    var folderCursorKey by remember(resetKey) { mutableStateOf<String?>(null) }

    var splitRatio by remember(viewState.splitRatio) { mutableStateOf(viewState.splitRatio) }

    val indexByKey = remember(rows) { rows.withIndex().associate { (index, row) -> row.key to index } }
    val selectedItems = remember(selectedEntries) { selectedEntries.toSet() }
    val cursorKey = remember(rows, selectedEntries) {
        val cursorItem = selectedEntries.lastOrNull()

        rows.firstOrNull { it is FileRow.File && it.file.item == cursorItem }?.key
    }
    val cursorIndex = folderCursorKey?.let { indexByKey[it] } ?: cursorKey?.let { indexByKey[it] }

    // The list keeps the first visible row by its key, which lands mid-list once the rows are ordered differently
    val layoutKey = Triple(viewState.viewMode, viewState.sortKey, viewState.ascending)
    var shownLayoutKey by remember { mutableStateOf(layoutKey) }

    LaunchedEffect(layoutKey) {
        if (layoutKey != shownLayoutKey) {
            shownLayoutKey = layoutKey
            listState.scrollToItem(cursorIndex ?: 0)
        }
    }

    fun moveTo(index: Int) {
        val row = rows.getOrNull(index) ?: return

        when (row) {
            is FileRow.File -> {
                folderCursorKey = null
                onKeyboardSelect(row.file.item)
            }

            is FileRow.Folder -> folderCursorKey = row.key
        }

        scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo

            if (visible.isEmpty()) return@launch

            val first = visible.first().index
            val last = visible.last().index

            if (index <= first) {
                listState.scrollToItem(index)
            } else if (index >= last) {
                listState.scrollToItem((index - visible.size + 2).coerceAtLeast(0))
            }
        }
    }

    fun onKey(keyEvent: KeyEvent): Boolean {
        if (keyEvent.type != KeyEventType.KeyDown || rows.isEmpty()) return false

        val index = cursorIndex
        val row = index?.let { rows[it] }
        val isTree = viewState.viewMode == FilesViewMode.FolderTree

        return when {
            keyEvent.matchesBinding(KeybindingOption.DOWN) -> {
                moveTo(if (index == null) 0 else (index + 1).coerceAtMost(rows.lastIndex))
                true
            }

            keyEvent.matchesBinding(KeybindingOption.UP) -> {
                moveTo(if (index == null) 0 else (index - 1).coerceAtLeast(0))
                true
            }

            isTree && keyEvent.key == Key.DirectionRight && index != null && row is FileRow.Folder -> {
                val next = rows.getOrNull(index + 1)

                if (!row.isExpanded) {
                    onFolderToggle(row.path)
                } else if (next != null && next.depth > row.depth) {
                    moveTo(index + 1)
                }

                true
            }

            isTree && keyEvent.key == Key.DirectionLeft && index != null && row != null -> {
                if (row is FileRow.Folder && row.isExpanded) {
                    onFolderToggle(row.path)
                } else {
                    val parent = (index - 1 downTo 0).firstOrNull { rows[it].depth == row.depth - 1 }

                    if (parent != null) moveTo(parent)
                }

                true
            }

            else -> false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .onPreviewKeyEvent(::onKey),
    ) {
        if (viewState.viewMode == FilesViewMode.SplitColumns) {
            SplitColumnsHeader(
                ratio = splitRatio,
                onRatioDelta = { delta ->
                    splitRatio = (splitRatio + delta)
                        .coerceIn(SortSettingsCodec.MIN_SPLIT_RATIO, SortSettingsCodec.MAX_SPLIT_RATIO)
                },
                onRatioChangeFinished = { onSplitRatioChange(splitRatio) },
            )
        }

        ScrollableLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            items(items = rows, key = { it.key }) { row ->
                when (row) {
                    is FileRow.Folder -> FolderRow(
                        folder = row,
                        hasKeyboardFocus = isFocused && row.key == folderCursorKey,
                        onClick = {
                            folderCursorKey = row.key
                            focusRequester.requestFocus()
                            onFolderToggle(row.path)
                        },
                        onGenerateContextMenu = onGenerateFolderContextMenu?.let { generate -> { generate(row) } },
                        trailingAction = folderTrailingAction?.let { action ->
                            { isHovered -> action(row, isHovered) }
                        },
                    )

                    is FileRow.File -> ChangedFileRow(
                        icon = fileIcon(row.file.item),
                        iconColor = fileIconColor(row.file.item),
                        isSelected = row.file.item in selectedItems,
                        startPadding = if (viewState.viewMode == FilesViewMode.FolderTree) {
                            TREE_FOLDER_START + TREE_INDENT * row.depth + 10.dp
                        } else {
                            0.dp
                        },
                        onClick = {
                            folderCursorKey = null
                            focusRequester.requestFocus()
                            onFileClick(row.file.item)
                        },
                        onDoubleClick = onFileDoubleClick?.let { action -> { action(row.file.item) } },
                        onGenerateContextMenu = { onGenerateContextMenu(row.file.item) },
                        trailingAction = fileTrailingAction?.let { action ->
                            { isHovered -> action(row.file.item, isHovered) }
                        },
                    ) {
                        when (viewState.viewMode) {
                            FilesViewMode.FlatList -> if (viewState.sortKey == FileSortKey.FileName) {
                                FileNameFirstContent(row.file.fileName, row.file.directory)
                            } else {
                                DirectoryFirstContent(row.file.fileName, row.file.directory)
                            }

                            FilesViewMode.SplitColumns -> SplitColumnsContent(
                                fileName = row.file.fileName,
                                directory = row.file.directory,
                                path = row.file.path,
                                ratio = splitRatio,
                            )

                            FilesViewMode.FolderTree -> FileNameText(
                                row.file.fileName,
                                Modifier.padding(end = ROW_END_PADDING),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitColumnsHeader(
    ratio: Float,
    /** Change of the ratio, as a share of the columns' width. */
    onRatioDelta: (Float) -> Unit,
    onRatioChangeFinished: () -> Unit,
) {
    var columnsWidthPx by remember { mutableStateOf(0f) }
    val gapPx = with(LocalDensity.current) { COLUMN_GAP.toPx() }
    val dragState = rememberDraggableState { delta ->
        val available = columnsWidthPx - gapPx

        if (available > 0) {
            onRatioDelta(delta / available)
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(start = ICON_AREA_WIDTH, end = ROW_END_PADDING)
                .onSizeChanged { columnsWidthPx = it.width.toFloat() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColumnHeaderText(stringResource(Res.string.files_changed_column_file), Modifier.weight(ratio))

            Box(
                modifier = Modifier
                    .width(COLUMN_GAP)
                    .fillMaxHeight()
                    .pointerHoverIcon(resizePointerIconEast)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = { onRatioChangeFinished() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.6f)
                        .background(MaterialTheme.colors.onBackground.copy(alpha = 0.2f))
                )
            }

            ColumnHeaderText(stringResource(Res.string.files_changed_column_directory), Modifier.weight(1f - ratio))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colors.onBackground.copy(alpha = 0.08f))
        )
    }
}

@Composable
private fun ColumnHeaderText(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FolderRow(
    folder: FileRow.Folder,
    hasKeyboardFocus: Boolean,
    onClick: () -> Unit,
    onGenerateContextMenu: (() -> List<ContextMenuElement>)?,
    trailingAction: (@Composable BoxScope.(isHovered: Boolean) -> Unit)?,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    @Composable
    fun FolderContent() {
        Row(
            modifier = Modifier
                .height(MaterialTheme.linesHeight.fileHeight)
                .fillMaxWidth()
                .backgroundIf(hasKeyboardFocus, MaterialTheme.colors.backgroundSelected.copy(alpha = 0.6f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderEntryContent(
                label = folder.label,
                count = folder.fileCount,
                isExpanded = folder.isExpanded,
                startPadding = TREE_FOLDER_START + TREE_INDENT * folder.depth,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .handMouseClickable { onClick() }
            .hoverable(hoverInteraction)
    ) {
        if (onGenerateContextMenu != null) {
            ContextMenu(items = onGenerateContextMenu) { FolderContent() }
        } else {
            FolderContent()
        }

        trailingAction?.invoke(this, isHovered)
    }
}

@Composable
private fun ChangedFileRow(
    icon: ImageVector,
    iconColor: Color,
    isSelected: Boolean,
    startPadding: Dp,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    onGenerateContextMenu: () -> List<ContextMenuElement>,
    trailingAction: (@Composable BoxScope.(isHovered: Boolean) -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    // The first click of a double click selects the file, which rebuilds the rows and so the lambda. A new key would
    // restart the double click detection and lose that first click.
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)

    Box(
        modifier = Modifier
            .handMouseClickable { onClick() }
            .then(if (onDoubleClick != null) Modifier.onDoubleClick { currentOnDoubleClick?.invoke() } else Modifier)
            .fillMaxWidth()
            .hoverable(hoverInteraction)
    ) {
        ContextMenu(items = onGenerateContextMenu) {
            Row(
                modifier = Modifier
                    .height(MaterialTheme.linesHeight.fileHeight)
                    .fillMaxWidth()
                    .backgroundIf(isSelected, MaterialTheme.colors.backgroundSelected)
                    .padding(start = startPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(16.dp),
                    tint = iconColor,
                )

                content()
            }
        }

        trailingAction?.invoke(this, isHovered)
    }
}

/** `dir/` in muted text, then the file name, as Leaf has always shown changed files. */
@Composable
private fun RowScope.DirectoryFirstContent(fileName: String, directory: String) {
    if (directory.isNotEmpty()) {
        Text(
            text = directory,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.body2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )

        Text(
            text = "/",
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )
    }

    FileNameText(fileName, Modifier.padding(end = ROW_END_PADDING))
}

/** The file name, then its directory in muted text, so a list sorted by file name is easy to scan. */
@Composable
private fun RowScope.FileNameFirstContent(fileName: String, directory: String) {
    FileNameText(fileName, Modifier)

    if (directory.isNotEmpty()) {
        Text(
            text = directory,
            modifier = Modifier
                .padding(start = 10.dp, end = ROW_END_PADDING)
                .weight(1f, fill = false),
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.body2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )
    } else {
        Spacer(Modifier.width(ROW_END_PADDING))
    }
}

/** The file name and directory in two columns. The directory keeps its end visible when it doesn't fit. */
@Composable
private fun RowScope.SplitColumnsContent(fileName: String, directory: String, path: String, ratio: Float) {
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(end = ROW_END_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            modifier = Modifier.weight(ratio),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
        )

        Spacer(Modifier.width(COLUMN_GAP))

        DelayedTooltip(text = path, modifier = Modifier.weight(1f - ratio)) {
            StartEllipsisPathText(
                text = directory,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
            )
        }
    }
}

@Composable
private fun FileNameText(fileName: String, modifier: Modifier) {
    Text(
        text = fileName,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackground,
    )
}
