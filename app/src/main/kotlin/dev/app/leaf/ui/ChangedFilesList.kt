// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.files_changed_column_directory
import dev.app.leaf.app.generated.resources.files_changed_column_file
import dev.app.leaf.app.generated.resources.files_changed_renamed_from_similar
import dev.app.leaf.app.generated.resources.files_changed_renamed_from_unchanged
import dev.app.leaf.app.generated.resources.files_changed_untracked
import dev.app.leaf.app.generated.resources.files_changed_untracked_tooltip
import dev.app.leaf.domain.sorting.FileGroup
import dev.app.leaf.domain.sorting.FileItem
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FileSortKey
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RenamedFrom
import dev.app.leaf.domain.sorting.SortSettingsCodec
import dev.app.leaf.extensions.backgroundIf
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.onDoubleClick
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.modifyFile
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

/** Between a renamed file's name and where it came from, and after the hint. */
private val HINT_GAP = 10.dp

/** Room kept after the longest name when a double-click fits the name column to it. */
private val FIT_SLACK = 4.dp

/** Only the longest names are measured when fitting: they are the widest in nearly every font. */
private const val FIT_MEASURED_NAMES = 300

/** The most that a renamed file's name takes of the row, and that its hint takes of what the name leaves. */
private const val NAME_MAX_SHARE = 0.55f
private const val HINT_MAX_SHARE = 0.8f

/** How much of its colour a file name and icon keep in a [FilesSection]'s rows (fork-only). */
private const val MUTED_ALPHA = 0.8f
private const val MUTED_ICON_ALPHA = 0.75f

/** Set while a [FilesSection]'s rows are drawn: their file names and icons are a little quieter than the list's own. */
private val LocalMutedRows = compositionLocalOf { false }

private fun Color.mutedIf(isMuted: Boolean, alpha: Float = MUTED_ALPHA): Color = if (isMuted) copy(alpha = alpha) else this

/**
 * Rows that follow a [ChangedFilesList]'s own, under a header of their own (fork-only, upstream issue 260): the Staged
 * pane lists the files of the commit that Amend rewrites there. They scroll with the list and take the same view (sort,
 * flat list, columns or tree), but only open something: they have no stage button, double-click or keyboard position.
 * [rows] is empty while the section is closed, and [message] shows in their place when there is one.
 */
class FilesSection<S>(
    val header: @Composable () -> Unit,
    val rows: List<FileRow<S>>,
    /** The row that is selected, by identity. */
    val selected: S?,
    val fileIcon: (S) -> ImageVector,
    val fileIconColor: @Composable (S) -> Color,
    val onFileClick: (S) -> Unit,
    val onFolderToggle: (path: String) -> Unit,
    val onGenerateContextMenu: (S) -> List<ContextMenuElement>,
    val message: (@Composable () -> Unit)? = null,
)

/**
 * Changed files as a flat list, split columns or a folder tree, for the Files changed, Staged and Unstaged panes.
 * Up and Down move the selection; in the tree, Left and Right close and open folders or move to the parent or first
 * child. Fork-only: [FileRow.Group] rows head groups of rows (Unstaged's, upstream issue 345), which the keys pass over.
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
    /** Rows after the list's own, under a header of their own (fork-only). */
    section: FilesSection<*>? = null,
    /** Closes or opens a group's rows (fork-only). */
    onGroupToggle: (FileGroup) -> Unit = {},
    /** Closes or opens a folder of a group, which can be in two groups (fork-only). [onFolderToggle] by default. */
    onGroupFolderToggle: ((group: FileGroup, path: String) -> Unit)? = null,
    /** Drawn over the end of a group's header while it is hovered (fork-only). */
    groupTrailingAction: (@Composable BoxScope.(group: FileRow.Group, isHovered: Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    // A folder the keyboard moved to. On files, the selection is the position.
    var folderCursorKey by remember(resetKey) { mutableStateOf<String?>(null) }

    var splitRatio by remember(viewState.splitRatio) { mutableStateOf(viewState.splitRatio) }

    val textMeasurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.body2
    val fitSlackPx = with(LocalDensity.current) { FIT_SLACK.toPx() }

    val indexByKey = remember(rows) { rows.withIndex().associate { (index, row) -> row.key to index } }
    val selectedItems = remember(selectedEntries) { selectedEntries.toSet() }
    val cursorKey = remember(rows, selectedEntries) {
        val cursorItem = selectedEntries.lastOrNull()

        rows.firstOrNull { it is FileRow.File && it.file.item == cursorItem }?.key
    }
    val cursorIndex = folderCursorKey?.let { indexByKey[it] } ?: cursorKey?.let { indexByKey[it] }

    // The list keeps the first visible row by its key, which lands mid-list once the rows are ordered differently.
    // Fork-only: grouping the rows orders them differently too
    val isGrouped = rows.any { it is FileRow.Group }
    val layoutKey = listOf(viewState.viewMode, viewState.sortKey, viewState.ascending, isGrouped)
    var shownLayoutKey by remember { mutableStateOf(layoutKey) }

    LaunchedEffect(layoutKey) {
        if (layoutKey != shownLayoutKey) {
            shownLayoutKey = layoutKey
            listState.scrollToItem(cursorIndex ?: 0)
        }
    }

    fun toggle(folder: FileRow.Folder) {
        val group = folder.group

        if (group != null && onGroupFolderToggle != null) {
            onGroupFolderToggle(group, folder.path)
        } else {
            onFolderToggle(folder.path)
        }
    }

    /** The row that [step] goes to from [index], passing over groups' headers (fork-only), or null past the end. */
    fun stepped(index: Int, step: Int): Int? = generateSequence(index + step) { it + step }
        .takeWhile { it in rows.indices }
        .firstOrNull { rows[it] !is FileRow.Group }

    fun moveTo(index: Int) {
        val row = rows.getOrNull(index) ?: return

        when (row) {
            is FileRow.File -> {
                folderCursorKey = null
                onKeyboardSelect(row.file.item)
            }

            is FileRow.Folder -> folderCursorKey = row.key

            // The keys pass over them
            is FileRow.Group -> return
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
                (if (index == null) stepped(-1, 1) else stepped(index, 1) ?: index)?.let(::moveTo)
                true
            }

            keyEvent.matchesBinding(KeybindingOption.UP) -> {
                (if (index == null) stepped(-1, 1) else stepped(index, -1) ?: index)?.let(::moveTo)
                true
            }

            isTree && keyEvent.key == Key.DirectionRight && index != null && row is FileRow.Folder -> {
                val next = rows.getOrNull(index + 1)

                if (!row.isExpanded) {
                    toggle(row)
                } else if (next != null && next.depth > row.depth) {
                    moveTo(index + 1)
                }

                true
            }

            isTree && keyEvent.key == Key.DirectionLeft && index != null && row != null -> {
                if (row is FileRow.Folder && row.isExpanded) {
                    toggle(row)
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
                onFitToContent = { availablePx ->
                    val longestName = rows.asSequence()
                        .filterIsInstance<FileRow.File<T>>()
                        .map { it.file.fileName }
                        .sortedByDescending { it.length }
                        .take(FIT_MEASURED_NAMES)
                        .maxOfOrNull { textMeasurer.measure(it, nameStyle, softWrap = false, maxLines = 1).size.width }

                    fittedSplitRatio(longestName, fitSlackPx, availablePx)?.let { fitted ->
                        splitRatio = fitted
                        onSplitRatioChange(fitted)
                    }
                },
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
                            toggle(row)
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
                        FileRowContent(row.file, viewState, splitRatio)
                    }

                    is FileRow.Group -> FilesGroupHeader(
                        title = fileGroupTitle(row.group),
                        count = row.fileCount,
                        isCollapsed = !row.isExpanded,
                        onToggle = {
                            folderCursorKey = null
                            focusRequester.requestFocus()
                            onGroupToggle(row.group)
                        },
                        trailingAction = groupTrailingAction?.let { action ->
                            { isHovered -> action(row, isHovered) }
                        },
                    )
                }
            }

            if (section != null) {
                sectionItems(
                    section = section,
                    viewState = viewState,
                    splitRatio = splitRatio,
                    // As the list's own rows do, so the arrow keys stay with the list that was clicked
                    onRowClicked = {
                        folderCursorKey = null
                        focusRequester.requestFocus()
                    },
                )
            }
        }
    }
}

/**
 * The content of a file's row after its icon: the name and directory in the view's layout, and where a renamed file came
 * from. Shared by the list's own rows and a [FilesSection]'s.
 */
@Composable
private fun RowScope.FileRowContent(file: FileItem<*>, viewState: FilesViewState, splitRatio: Float) {
    // Where a renamed file came from, right after its name: the directory, which is long and which
    // the pane often can't fit, is what gives way to it. Fork-only: and that git doesn't track a
    // file yet, in a comparison with the working tree
    val renamedFrom = file.renamedFrom
    val isUntracked = file.isUntracked
    val renamedHint: (@Composable RowScope.() -> Unit)? = if (renamedFrom == null && !isUntracked) {
        null
    } else {
        {
            if (renamedFrom != null) RenamedFromHint(renamedFrom, file.path)
            if (isUntracked) UntrackedTag(Modifier.padding(start = if (renamedFrom != null) 6.dp else 0.dp))
        }
    }

    when (viewState.viewMode) {
        FilesViewMode.FlatList -> if (viewState.sortKey == FileSortKey.FileName) {
            FileNameFirstContent(file.fileName, file.directory, renamedHint)
        } else {
            DirectoryFirstContent(file.fileName, file.directory, renamedHint)
        }

        FilesViewMode.SplitColumns -> SplitColumnsContent(
            fileName = file.fileName,
            directory = file.directory,
            path = file.path,
            ratio = splitRatio,
            renamedHint = renamedHint,
        )

        FilesViewMode.FolderTree -> {
            FileNameText(file.fileName, Modifier.nameBesides(renamedHint))

            if (renamedHint == null) {
                Spacer(Modifier.width(ROW_END_PADDING))
            } else {
                Spacer(Modifier.width(HINT_GAP))
                renamedHint()
                Spacer(Modifier.width(ROW_END_PADDING))
            }
        }
    }
}

/** A [FilesSection]'s header and rows, after the list's own (fork-only). */
private fun <S> LazyListScope.sectionItems(
    section: FilesSection<S>,
    viewState: FilesViewState,
    splitRatio: Float,
    onRowClicked: () -> Unit,
) {
    item(key = SECTION_HEADER_KEY) { section.header() }

    section.message?.let { message -> item(key = SECTION_MESSAGE_KEY) { message() } }

    // Their keys can't be the list's own: a folder row's `dir:<path>` is the same for the same folder in both
    items(items = section.rows, key = { SECTION_ROW_KEY_PREFIX + it.key }) { row ->
        CompositionLocalProvider(LocalMutedRows provides true) {
            when (row) {
                is FileRow.Folder -> FolderRow(
                    folder = row,
                    hasKeyboardFocus = false,
                    onClick = {
                        onRowClicked()
                        section.onFolderToggle(row.path)
                    },
                    onGenerateContextMenu = null,
                    trailingAction = null,
                )

                is FileRow.File -> ChangedFileRow(
                    icon = section.fileIcon(row.file.item),
                    iconColor = section.fileIconColor(row.file.item),
                    isSelected = row.file.item === section.selected,
                    startPadding = if (viewState.viewMode == FilesViewMode.FolderTree) {
                        TREE_FOLDER_START + TREE_INDENT * row.depth + 10.dp
                    } else {
                        0.dp
                    },
                    onClick = {
                        onRowClicked()
                        section.onFileClick(row.file.item)
                    },
                    onDoubleClick = null,
                    onGenerateContextMenu = { section.onGenerateContextMenu(row.file.item) },
                    trailingAction = null,
                ) {
                    FileRowContent(row.file, viewState, splitRatio)
                }

                // A section's rows aren't grouped
                is FileRow.Group -> Unit
            }
        }
    }
}

private const val SECTION_HEADER_KEY = "section:header"
private const val SECTION_MESSAGE_KEY = "section:message"
private const val SECTION_ROW_KEY_PREFIX = "section:"

@Composable
private fun SplitColumnsHeader(
    ratio: Float,
    /** Change of the ratio, as a share of the columns' width. */
    onRatioDelta: (Float) -> Unit,
    onRatioChangeFinished: () -> Unit,
    /** Double-click on the separator, with the width that the two columns share. */
    onFitToContent: (availablePx: Float) -> Unit,
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
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onFitToContent(columnsWidthPx - gapPx) })
                    }
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

/**
 * The ratio at which the name column just holds [longestNamePx] plus [slackPx], within the bounds a drag keeps. Null
 * when there is no name or no room to measure against, which leaves the columns as they are.
 */
internal fun fittedSplitRatio(longestNamePx: Int?, slackPx: Float, availablePx: Float): Float? {
    if (longestNamePx == null || availablePx <= 0f) return null

    return ((longestNamePx + slackPx) / availablePx)
        .coerceIn(SortSettingsCodec.MIN_SPLIT_RATIO, SortSettingsCodec.MAX_SPLIT_RATIO)
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
                    tint = iconColor.mutedIf(LocalMutedRows.current, MUTED_ICON_ALPHA),
                )

                content()
            }
        }

        trailingAction?.invoke(this, isHovered)
    }
}

/** `dir/` in muted text, then the file name, as Leaf has always shown changed files. */
@Composable
private fun RowScope.DirectoryFirstContent(
    fileName: String,
    directory: String,
    renamedHint: (@Composable RowScope.() -> Unit)?,
) {
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

    FileNameText(fileName, Modifier.nameBesides(renamedHint))

    if (renamedHint != null) {
        Spacer(Modifier.width(HINT_GAP))
        renamedHint()
    }

    Spacer(Modifier.width(ROW_END_PADDING))
}

/** The file name, then its directory in muted text, so a list sorted by file name is easy to scan. */
@Composable
private fun RowScope.FileNameFirstContent(
    fileName: String,
    directory: String,
    renamedHint: (@Composable RowScope.() -> Unit)?,
) {
    FileNameText(fileName, Modifier.nameBesides(renamedHint))

    if (renamedHint != null) {
        Spacer(Modifier.width(HINT_GAP))
        renamedHint()
    }

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

/**
 * The file name and directory in two columns. The directory keeps its end visible when it doesn't fit. A renamed file's
 * hint leads the directory's column, so it is the directory that is cut short.
 */
@Composable
private fun RowScope.SplitColumnsContent(
    fileName: String,
    directory: String,
    path: String,
    ratio: Float,
    renamedHint: (@Composable RowScope.() -> Unit)?,
) {
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
            color = MaterialTheme.colors.onBackground.mutedIf(LocalMutedRows.current),
        )

        Spacer(Modifier.width(COLUMN_GAP))

        if (renamedHint == null) {
            DelayedTooltip(text = path, modifier = Modifier.weight(1f - ratio)) {
                StartEllipsisPathText(
                    text = directory,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
            }
        } else {
            Row(
                modifier = Modifier.weight(1f - ratio),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                renamedHint()

                Spacer(Modifier.width(HINT_GAP))

                DelayedTooltip(text = path, modifier = Modifier.weight(1f, fill = false)) {
                    StartEllipsisPathText(
                        text = directory,
                        modifier = Modifier,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                    )
                }
            }
        }
    }
}

/**
 * `← Lexer.kt` after a renamed file's name: only the part of the old path that changed ([RenamedFrom.label]), with the
 * whole path and how much of the content is alike in its tooltip. The arrow has the colour of the row's icon, and the
 * label is stronger than the muted directory, as this is what the row is about.
 */
@Composable
private fun RowScope.RenamedFromHint(renamedFrom: RenamedFrom, path: String) {
    val tooltip = when {
        renamedFrom.isContentUnchanged ->
            stringResource(Res.string.files_changed_renamed_from_unchanged, renamedFrom.path)

        else -> stringResource(
            Res.string.files_changed_renamed_from_similar,
            renamedFrom.path,
            "${renamedFrom.similarity}%",
        )
    }
    val arrowColor = MaterialTheme.colors.modifyFile
    val text = remember(renamedFrom, path, arrowColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = arrowColor)) { append("\u2190") }
            append(" ${renamedFrom.label(path)}")
        }
    }

    DelayedTooltip(text = tooltip, modifier = Modifier.atMost(HINT_MAX_SHARE)) {
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.75f),
        )
    }
}

/** "untracked" after the name of a new file that git doesn't track yet (fork-only, `docs/fork/compare.md`, 7.2). */
@Composable
private fun UntrackedTag(modifier: Modifier = Modifier) {
    DelayedTooltip(text = stringResource(Res.string.files_changed_untracked_tooltip), modifier = modifier) {
        Text(
            text = stringResource(Res.string.files_changed_untracked),
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp),
        )
    }
}

/** The file name, which a renamed file's hint can't leave without room: at most [NAME_MAX_SHARE] of the row. */
private fun Modifier.nameBesides(renamedHint: (@Composable RowScope.() -> Unit)?): Modifier =
    if (renamedHint == null) this else atMost(NAME_MAX_SHARE)

/** Measures what it's given with at most [share] of the width on offer, so that what comes after keeps some. */
private fun Modifier.atMost(share: Float): Modifier = layout { measurable, constraints ->
    val limit = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = (constraints.maxWidth * share).toInt().coerceAtLeast(constraints.minWidth))
    } else {
        constraints
    }
    val placeable = measurable.measure(limit)

    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
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
        color = MaterialTheme.colors.onBackground.mutedIf(LocalMutedRows.current),
    )
}
