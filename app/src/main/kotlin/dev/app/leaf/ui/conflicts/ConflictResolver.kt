// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.conflicts.ChunkPlace
import dev.app.leaf.domain.conflicts.ColumnShares
import dev.app.leaf.domain.conflicts.DEFAULT_FILE_LIST_WIDTH
import dev.app.leaf.domain.conflicts.fileListWidthAfterDrag
import dev.app.leaf.domain.conflicts.columnRows
import dev.app.leaf.domain.conflicts.conflictChunks
import dev.app.leaf.domain.conflicts.placeOf
import dev.app.leaf.domain.conflicts.resultRows
import dev.app.leaf.domain.conflicts.rowOf
import dev.app.leaf.domain.conflicts.sidesRows
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictStages
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.domain.models.canOpenInMergeTool
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.getErrorText
import dev.app.leaf.ui.status.named
import dev.app.leaf.viewmodels.ConflictResolverState
import dev.app.leaf.viewmodels.ConflictResolverViewModel
import dev.app.leaf.viewmodels.ResolverFile
import dev.app.leaf.viewmodels.ResolverFileEntry
import dev.app.leaf.viewmodels.ResolverPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The built-in conflict resolver, over the whole tab (`docs/fork/conflict-resolver.md`, steps 2 and 3): the conflicted
 * files on the left, and the one picked in three columns, or with the sides on top of the result. [onConfirmUseSide]
 * asks before a side is taken for a whole file, as "Use ours" and "Use theirs" do elsewhere.
 */
@Composable
fun ConflictResolver(
    viewModel: ConflictResolverViewModel,
    onConfirmUseSide: (StatusEntry, ConflictSide, ConflictSides?, onConfirm: () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val lists = remember { ResolverLists() }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    ConflictResolverContent(
        state = state,
        lists = lists,
        scrollToChunk = viewModel.scrollToChunk,
        actions = ResolverActions(
            onClose = viewModel::close,
            onSelectFile = viewModel::selectFile,
            onPreviousConflict = viewModel::previousConflict,
            onNextConflict = viewModel::nextConflict,
            onToggleBase = viewModel::toggleBase,
            onSetLayout = viewModel::setLayout,
            onStackedSplitChanged = viewModel::setStackedSplit,
            onColumnSharesChanged = viewModel::setColumnShares,
            onFileListWidthChanged = viewModel::setFileListWidth,
            onToggleWrapLines = viewModel::toggleWrapLines,
            onToggleFocus = viewModel::toggleFocus,
            onSave = { viewModel.save() },
            onMarkResolved = { viewModel.markResolved() },
            onOpenInExternalTool = viewModel::openInExternalTool,
            onMarkResolvedAsItIs = viewModel::markResolvedAsItIs,
            onResolveAgain = viewModel::resolveAgain,
            onUseSide = { entry, side, sides -> onConfirmUseSide(entry, side, sides) { viewModel.useSide(side) } },
            onAnswerUnsaved = viewModel::answerUnsavedChanges,
            onReload = viewModel::reload,
            onDismissPrompt = viewModel::dismissPrompt,
            picks = PickActions(
                onTake = { chunk, side ->
                    viewModel.take(chunk, side)
                    viewModel.selectConflict(chunk)
                },
                onResolve = { chunk, resolution ->
                    viewModel.resolve(chunk, resolution)
                    viewModel.selectConflict(chunk)
                },
                onSetApplied = viewModel::setApplied,
                onDrop = { chunk, side ->
                    viewModel.drop(chunk, side)
                    viewModel.selectConflict(chunk)
                },
            ),
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.F7 -> {
                        if (event.isShiftPressed) viewModel.previousConflict() else viewModel.nextConflict()
                        true
                    }

                    event.matchesBinding(KeybindingOption.TEXT_ACCEPT) -> {
                        viewModel.markResolved()
                        true
                    }

                    isFocusShortcut(event) -> {
                        viewModel.toggleFocus()
                        true
                    }

                    else -> false
                }
            },
    )
}

/** What the resolver's buttons do, apart from the columns'. */
class ResolverActions(
    val onClose: () -> Unit,
    val onSelectFile: (String) -> Unit,
    val onPreviousConflict: () -> Unit,
    val onNextConflict: () -> Unit,
    val onToggleBase: () -> Unit,
    val onSetLayout: (ConflictResolverLayout) -> Unit,
    /** The stacked layout's handle was dragged: the top pair's share of the height. */
    val onStackedSplitChanged: (Float) -> Unit,
    /** A gutter of the Columns layout was dragged. */
    val onColumnSharesChanged: (ColumnShares) -> Unit,
    /** The file list's edge was dragged: its width in dp. */
    val onFileListWidthChanged: (Float) -> Unit,
    val onToggleWrapLines: () -> Unit,
    /** Focus: hides the rest of Leaf around the resolver, and the file list. */
    val onToggleFocus: () -> Unit,
    val onSave: () -> Unit,
    val onMarkResolved: () -> Unit,
    val onOpenInExternalTool: () -> Unit,
    val onMarkResolvedAsItIs: () -> Unit,
    val onResolveAgain: () -> Unit,
    val onUseSide: (StatusEntry, ConflictSide, ConflictSides?) -> Unit,
    val onAnswerUnsaved: (save: Boolean?) -> Unit,
    val onReload: () -> Unit,
    val onDismissPrompt: () -> Unit,
    val picks: PickActions,
)

/** The resolver as [state] shows it, without its view model, so that tests can render it. */
@Composable
fun ConflictResolverContent(
    state: ConflictResolverState,
    lists: ResolverLists,
    actions: ResolverActions,
    modifier: Modifier = Modifier,
    /** Conflicts (their chunks) to scroll to, as F7 moves to them. */
    scrollToChunk: Flow<Int> = emptyFlow(),
) {
    val file = state.file as? ResolverFile.Loaded
    val merge = file?.merge?.takeUnless { file.workingFileState == WorkingFileState.NO_MARKERS && !file.resolveAgain }
    val entry = state.files.firstOrNull { it.entry.filePath == state.file.path }?.entry
    val shown = remember { ShownPlace() }
    // A copy while the file list's edge is dragged; the view model gets the width when the drag ends
    var fileListWidth by remember(state.fileListWidth) { mutableFloatStateOf(state.fileListWidth) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Toolbar(state, file, merge, entry, actions)

        state.prompt?.let { Prompt(it, state.file.path.orEmpty(), actions) }

        Row(Modifier.weight(1f)) {
            // In Focus, the toolbar's file name lists the files instead
            if (!state.focused) {
                FileList(state.files, state.file.path, fileListWidth, actions.onSelectFile)
                FileListEdge(
                    onDrag = { delta -> fileListWidth = fileListWidthAfterDrag(fileListWidth, delta) },
                    onDragStopped = { actions.onFileListWidthChanged(fileListWidth) },
                    onReset = {
                        fileListWidth = DEFAULT_FILE_LIST_WIDTH
                        actions.onFileListWidthChanged(DEFAULT_FILE_LIST_WIDTH)
                    },
                )
            }

            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    state.done -> AllResolved(actions.onClose)
                    state.file is ResolverFile.Loading -> Message(stringResource(Res.string.conflict_resolver_loading))
                    state.file is ResolverFile.Failed -> Message(state.file.error.getErrorText())
                    file == null -> Message(stringResource(Res.string.conflict_resolver_nothing))
                    file.file.content !is ConflictContent.Text -> ChoicePanel(file.file, entry, actions)
                    merge == null -> NoMarkersPanel(file.file.path, actions)
                    else -> Column {
                        if (file.workingFileState == WorkingFileState.DIFFERENT) {
                            DifferentMarkersStrip(file.file)
                        }

                        val conflicts = remember(merge) { merge.conflictChunks() }
                        val layout = state.layout
                        val currentMerge by rememberUpdatedState(merge)
                        val showBase by rememberUpdatedState(state.showBase)

                        // Opens a file at its first conflict, and keeps the place when the layout changes
                        LaunchedEffect(file.file.path, layout) {
                            val previous = shown.layout.takeIf { shown.path == file.file.path }

                            shown.path = file.file.path
                            shown.layout = layout

                            when (previous) {
                                null -> {
                                    lists.sideways.scrollTo(0f)
                                    lists.show(currentMerge, layout, showBase, conflicts.firstOrNull(), false)
                                }

                                layout -> Unit
                                else -> lists.keepPlace(currentMerge, showBase, from = previous, to = layout)
                            }
                        }

                        LaunchedEffect(scrollToChunk, layout) {
                            scrollToChunk.collect { chunk ->
                                lists.show(currentMerge, layout, showBase, chunk, animate = true)
                            }
                        }

                        // Lines that don't wrap scroll sideways together, in both layouts
                        val sideways = if (state.wrapLines) null else lists.sideways

                        CompositionLocalProvider(LocalSidewaysScroll provides sideways) {
                            LayoutView(merge, file, conflicts, state, lists, actions, Modifier.weight(1f))
                        }

                        ResolverLegend(stacked = layout == ConflictResolverLayout.STACKED)
                    }
                }
            }
        }
    }
}

/** The file in [state]'s layout. */
@Composable
private fun LayoutView(
    merge: TextMerge,
    file: ResolverFile.Loaded,
    conflicts: List<Int>,
    state: ConflictResolverState,
    lists: ResolverLists,
    actions: ResolverActions,
    modifier: Modifier,
) {
    when (state.layout) {
        ConflictResolverLayout.COLUMNS -> {
            val rows = remember(merge, state.showBase) { merge.columnRows(state.showBase) }

            ResolverColumnsView(
                merge = merge,
                rows = rows,
                sides = file.file.sides,
                filePath = file.file.path,
                currentConflictChunk = conflicts.getOrNull(state.currentConflict),
                showBase = state.showBase,
                listState = lists.columns,
                actions = actions.picks,
                shares = state.columnShares,
                onSharesChanged = actions.onColumnSharesChanged,
                modifier = modifier,
            )
        }

        ConflictResolverLayout.STACKED -> {
            val sidesRows = remember(merge) { merge.sidesRows() }
            val resultRows = remember(merge, state.showBase) { merge.resultRows(state.showBase) }

            ResolverStackedView(
                merge = merge,
                sidesRows = sidesRows,
                resultRows = resultRows,
                sides = file.file.sides,
                filePath = file.file.path,
                currentConflictChunk = conflicts.getOrNull(state.currentConflict),
                showBase = state.showBase,
                split = state.stackedSplit,
                lists = lists,
                actions = actions.picks,
                onSplitChanged = actions.onStackedSplitChanged,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun Toolbar(
    state: ConflictResolverState,
    file: ResolverFile.Loaded?,
    merge: TextMerge?,
    entry: StatusEntry?,
    actions: ResolverActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DelayedTooltip(stringResource(Res.string.conflict_resolver_close_tooltip)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .handMouseClickable { actions.onClose() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(Res.drawable.arrow_back), contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(Res.string.conflict_resolver_close),
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        if (state.focused) {
            FileMenu(state.files, state.file.path, actions.onSelectFile)
        } else {
            Text(
                text = state.file.path.orEmpty(),
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }

        if (merge != null) {
            ConflictNavigation(merge, state.currentConflict, actions)
        }

        Spacer(Modifier.weight(1f))

        if (merge != null) {
            LayoutButtons(state.layout, actions.onSetLayout)

            ToggleButton(
                text = stringResource(Res.string.conflict_resolver_wrap),
                tooltip = stringResource(Res.string.conflict_resolver_wrap_tooltip),
                toggled = state.wrapLines,
                onClick = actions.onToggleWrapLines,
            )

            ToggleButton(
                text = stringResource(Res.string.conflict_resolver_base),
                tooltip = stringResource(Res.string.conflict_resolver_base_tooltip),
                toggled = state.showBase,
                onClick = actions.onToggleBase,
            )
        }

        if (entry?.canOpenInMergeTool == true && file != null) {
            DelayedTooltip(stringResource(Res.string.conflict_resolver_external_tool_tooltip)) {
                SecondaryButton(
                    text = stringResource(Res.string.conflict_resolver_external_tool),
                    textColor = MaterialTheme.colors.onBackground,
                    backgroundButton = MaterialTheme.colors.secondarySurface,
                    onClick = actions.onOpenInExternalTool,
                )
            }
        }

        if (merge != null) {
            DelayedTooltip(stringResource(Res.string.conflict_resolver_save_tooltip)) {
                SecondaryButton(
                    text = stringResource(Res.string.conflict_resolver_save),
                    onClick = actions.onSave,
                )
            }

            val open = merge.openConflicts

            DelayedTooltip(
                if (open > 0) {
                    stringResource(Res.string.conflict_resolver_mark_resolved_disabled, open)
                } else {
                    stringResource(Res.string.conflict_resolver_mark_resolved_tooltip)
                }
            ) {
                PrimaryButton(
                    text = stringResource(Res.string.conflict_resolver_mark_resolved),
                    enabled = open == 0,
                    onClick = actions.onMarkResolved,
                )
            }
        }

        val shortcut = focusShortcut()

        ToolbarIcon(
            icon = if (state.focused) Res.drawable.fullscreen_exit else Res.drawable.fullscreen,
            description = if (state.focused) {
                stringResource(Res.string.conflict_resolver_leave_focus, shortcut)
            } else {
                stringResource(Res.string.conflict_resolver_focus, shortcut)
            },
            toggled = state.focused,
            onClick = actions.onToggleFocus,
        )
    }
}

/** Columns or Stacked, as the diff pane's Unified and Split (`StateIcon`), with the tooltip as the description. */
@Composable
private fun LayoutButtons(layout: ConflictResolverLayout, onSetLayout: (ConflictResolverLayout) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (option in ConflictResolverLayout.entries) {
            val (icon, tooltip) = when (option) {
                ConflictResolverLayout.COLUMNS ->
                    Res.drawable.view_column to Res.string.conflict_resolver_layout_columns_tooltip

                ConflictResolverLayout.STACKED ->
                    Res.drawable.view_stacked to Res.string.conflict_resolver_layout_stacked_tooltip
            }
            val toggled = option == layout

            ToolbarIcon(icon, stringResource(tooltip), toggled, onClick = { if (!toggled) onSetLayout(option) })
        }
    }
}

/** An icon button of the toolbar, as the diff pane's `StateIcon`, with the tooltip as the description. */
@Composable
private fun ToolbarIcon(icon: DrawableResource, description: String, toggled: Boolean, onClick: () -> Unit) {
    DelayedTooltip(description) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = MaterialTheme.colors.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (toggled) MaterialTheme.colors.onSurface.copy(alpha = 0.2f) else Color.Transparent)
                .handMouseClickable { onClick() }
                .padding(4.dp)
                .size(20.dp),
        )
    }
}

/** Focus's shortcut, as the platform writes it: ⇧⌘F on macOS, Ctrl+Shift+F elsewhere. */
private fun focusShortcut() = if (currentOs == OS.MAC) "⇧⌘F" else "Ctrl+Shift+F"

/** Whether [event] is Focus's shortcut: see the other [isFocusShortcut]. */
private fun isFocusShortcut(event: KeyEvent): Boolean = event.type == KeyEventType.KeyDown && isFocusShortcut(
    key = event.key,
    cmd = event.isMetaPressed,
    ctrl = event.isCtrlPressed,
    shift = event.isShiftPressed,
    alt = event.isAltPressed,
)

/** Whether pressing [key] with these modifiers is Focus's shortcut: Shift and F with Cmd on macOS, with Ctrl elsewhere. */
internal fun isFocusShortcut(key: Key, cmd: Boolean, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean =
    key == Key.F && shift && !alt && if (currentOs == OS.MAC) cmd && !ctrl else ctrl && !cmd

/**
 * The file name in the toolbar while the file list is hidden (Focus): it opens a menu of the conflicted files, which
 * switches to one as the list does. Files resolved here are listed with a check, and can't be opened again.
 */
@Composable
private fun FileMenu(files: List<ResolverFileEntry>, current: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        DelayedTooltip(stringResource(Res.string.conflict_resolver_files_menu)) {
            Row(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .handMouseClickable { expanded = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = current.orEmpty(),
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(painterResource(Res.drawable.expand_more), contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (file in files) {
                val path = file.entry.filePath

                DropdownMenuItem(
                    enabled = !file.resolvedHere,
                    onClick = {
                        expanded = false
                        if (path != current) onSelect(path)
                    },
                ) {
                    Icon(
                        painter = painterResource(if (file.resolvedHere) Res.drawable.check else Res.drawable.warning),
                        contentDescription = null,
                        tint = if (file.resolvedHere) MaterialTheme.colors.addFile else MaterialTheme.colors.conflictFile,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = path,
                        style = MaterialTheme.typography.body2,
                        fontWeight = if (path == current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictNavigation(merge: TextMerge, current: Int, actions: ResolverActions) {
    val conflicts = merge.conflicts

    if (conflicts == 0) {
        Text(
            text = stringResource(Res.string.conflict_resolver_no_conflicts),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )

        return
    }

    DelayedTooltip(stringResource(Res.string.conflict_resolver_previous_tooltip)) {
        NavigationIcon(rotation = 180f, onClick = actions.onPreviousConflict)
    }

    DelayedTooltip(stringResource(Res.string.conflict_resolver_next_tooltip)) {
        NavigationIcon(rotation = 0f, onClick = actions.onNextConflict)
    }

    Text(
        text = stringResource(Res.string.conflict_resolver_conflict_counter, current + 1, conflicts),
        style = MaterialTheme.typography.body2,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF1E1E1E),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.conflictFile)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )

    val resolved = conflicts - merge.openConflicts
    val clean = merge.chunks.count { it is TextMerge.Chunk.Change }

    Text(
        text = stringResource(Res.string.conflict_resolver_summary, resolved, clean),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
    )
}

@Composable
private fun NavigationIcon(rotation: Float, onClick: () -> Unit) {
    Icon(
        painter = painterResource(Res.drawable.expand_more),
        contentDescription = null,
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .handMouseClickable { onClick() }
            .padding(2.dp)
            .rotate(rotation),
    )
}

@Composable
private fun ToggleButton(text: String, tooltip: String, toggled: Boolean, onClick: () -> Unit) {
    DelayedTooltip(tooltip) {
        SecondaryButton(
            text = text,
            textColor = if (toggled) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onBackground,
            backgroundButton = if (toggled) MaterialTheme.colors.primary else MaterialTheme.colors.secondarySurface,
            onClick = onClick,
        )
    }
}

@Composable
private fun FileList(files: List<ResolverFileEntry>, current: String?, width: Float, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .width(width.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(Res.string.conflict_resolver_files),
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        for (file in files) {
            val path = file.entry.filePath
            val selected = path == current

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colors.backgroundSelected else Color.Transparent)
                    .handMouseClickable(enabled = !file.resolvedHere) { onSelect(path) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(if (file.resolvedHere) Res.drawable.check else Res.drawable.warning),
                    contentDescription = null,
                    tint = if (file.resolvedHere) MaterialTheme.colors.addFile else MaterialTheme.colors.conflictFile,
                    modifier = Modifier.size(14.dp),
                )

                DelayedTooltip(path, modifier = Modifier.weight(1f)) {
                    Text(
                        text = path.substringAfterLast('/'),
                        style = MaterialTheme.typography.body2,
                        color = if (file.resolvedHere) MaterialTheme.colors.onBackgroundSecondary else MaterialTheme.colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(Res.string.conflict_resolver_files_hint),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** The edge between the file list and the file: dragging it resizes the list, and a double-click puts it back. */
@Composable
private fun FileListEdge(onDrag: (Float) -> Unit, onDragStopped: () -> Unit, onReset: () -> Unit) {
    Box(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colors.tertiarySurface)
            .dragHandle(Orientation.Horizontal, onDrag, onDragStopped, onReset),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colors.onBackground.copy(alpha = 0.12f)))
    }
}

@Composable
private fun Prompt(prompt: ResolverPrompt, path: String, actions: ResolverActions) {
    Strip(
        text = when (prompt) {
            is ResolverPrompt.UnsavedChanges -> stringResource(Res.string.conflict_resolver_unsaved, path)
            is ResolverPrompt.WorkingFileChanged -> stringResource(Res.string.conflict_resolver_changed_on_disk, path)
            is ResolverPrompt.Failed -> prompt.error.getErrorText()
        },
        color = if (prompt is ResolverPrompt.Failed) MaterialTheme.colors.error else MaterialTheme.colors.conflictFile,
    ) {
        when (prompt) {
            is ResolverPrompt.UnsavedChanges -> {
                StripButton(stringResource(Res.string.conflict_resolver_save)) { actions.onAnswerUnsaved(true) }
                StripButton(stringResource(Res.string.conflict_resolver_discard)) { actions.onAnswerUnsaved(false) }
                StripButton(stringResource(Res.string.conflict_resolver_cancel)) { actions.onAnswerUnsaved(null) }
            }

            is ResolverPrompt.WorkingFileChanged -> {
                StripButton(stringResource(Res.string.conflict_resolver_reload)) { actions.onReload() }
                StripButton(stringResource(Res.string.conflict_resolver_keep)) {
                    actions.onDismissPrompt()
                    prompt.keep()
                }
                StripButton(stringResource(Res.string.conflict_resolver_cancel)) { actions.onDismissPrompt() }
            }

            is ResolverPrompt.Failed -> StripButton(stringResource(Res.string.conflict_resolver_dismiss)) {
                actions.onDismissPrompt()
            }
        }
    }
}

@Composable
private fun DifferentMarkersStrip(file: ConflictedFile) {
    val working = (file.content as? ConflictContent.Text)?.workingFile ?: return
    val merge = (file.content as ConflictContent.Text).merge

    Strip(
        text = stringResource(Res.string.conflict_resolver_different_markers, working.conflicts, merge.conflicts),
        color = MaterialTheme.colors.conflictFile,
    ) {}
}

@Composable
private fun Strip(text: String, color: Color, buttons: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.weight(1f, fill = false),
        )

        buttons()
    }
}

@Composable
private fun StripButton(text: String, onClick: () -> Unit) {
    SecondaryButton(
        text = text,
        textColor = MaterialTheme.colors.onBackground,
        backgroundButton = MaterialTheme.colors.secondarySurface,
        onClick = onClick,
    )
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onBackgroundSecondary)
    }
}

/** A panel in the middle of the view: a title, a text, and buttons under them. */
@Composable
private fun Panel(title: String, text: String, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onBackground)
            Text(text = text, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onBackgroundSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}

@Composable
private fun NoMarkersPanel(path: String, actions: ResolverActions) {
    Panel(
        title = stringResource(Res.string.conflict_resolver_no_markers_title),
        text = stringResource(Res.string.conflict_resolver_no_markers, path),
    ) {
        PrimaryButton(text = stringResource(Res.string.conflict_resolver_mark_as_is), onClick = actions.onMarkResolvedAsItIs)
        StripButton(stringResource(Res.string.conflict_resolver_resolve_again)) { actions.onResolveAgain() }
    }
}

@Composable
private fun AllResolved(onClose: () -> Unit) {
    Panel(
        title = stringResource(Res.string.conflict_resolver_done_title),
        text = stringResource(Res.string.conflict_resolver_done),
    ) {
        PrimaryButton(text = stringResource(Res.string.conflict_resolver_done_back), onClick = onClose)
    }
}

/**
 * A conflict that three panes can't show (`conflict-resolver.md`, 2.4): what it is, and a choice of side. The cards with
 * previews come with step 5.
 */
@Composable
private fun ChoicePanel(file: ConflictedFile, entry: StatusEntry?, actions: ResolverActions) {
    val sides = file.sides
    val ours = ConflictSide.OURS.named(sides)
    val theirs = ConflictSide.THEIRS.named(sides)
    val content = file.content

    val text = when (content) {
        ConflictContent.Deleted -> if (file.stages == ConflictStages.DELETED_BY_US) {
            stringResource(Res.string.conflict_resolver_choice_deleted, ours, theirs)
        } else {
            stringResource(Res.string.conflict_resolver_choice_deleted, theirs, ours)
        }

        ConflictContent.Binary -> stringResource(Res.string.conflict_resolver_choice_binary)
        is ConflictContent.Lfs -> stringResource(
            Res.string.conflict_resolver_choice_lfs,
            content.ours?.let { "${it.oid.take(12)}…, ${it.size} B" } ?: "—",
            content.theirs?.let { "${it.oid.take(12)}…, ${it.size} B" } ?: "—",
        )

        ConflictContent.Submodule -> stringResource(
            Res.string.conflict_resolver_choice_submodule,
            file.ours?.objectId?.take(7) ?: "—",
            file.theirs?.objectId?.take(7) ?: "—",
        )

        is ConflictContent.SymbolicLink -> stringResource(
            Res.string.conflict_resolver_choice_link,
            content.oursTarget ?: "—",
            content.theirsTarget ?: "—",
        )

        is ConflictContent.TooLarge -> stringResource(
            Res.string.conflict_resolver_choice_too_large,
            content.limit / (1024 * 1024),
        )

        ConflictContent.OtherPaths -> stringResource(Res.string.conflict_resolver_choice_other_paths)
        is ConflictContent.Text -> ""
    }

    Panel(title = file.path, text = text) {
        if (content != ConflictContent.OtherPaths && entry != null) {
            for (side in ConflictSide.entries) {
                val name = side.named(sides)
                // Where that side deleted the file, taking it deletes it
                val label = if (file.conflict.version(side) == null) {
                    stringResource(Res.string.conflict_resolver_choice_delete, name)
                } else {
                    stringResource(Res.string.conflict_resolver_choice_use, name)
                }

                StripButton(label) { actions.onUseSide(entry, side, sides) }
            }

            if (entry.canOpenInMergeTool) {
                StripButton(stringResource(Res.string.conflict_resolver_external_tool)) { actions.onOpenInExternalTool() }
            }
        }
    }
}

/**
 * The file and the layout that the lists last showed: another file opens at its first conflict, and another layout
 * keeps the place.
 */
private class ShownPlace {
    var path: String? = null
    var layout: ConflictResolverLayout? = null
}

/** Rows above a conflict that the view scrolls to, for context. */
private const val CONTEXT_ROWS = 3

/** The chunk of each row of [layout]'s main list: the columns, or the stacked layout's result. */
private fun TextMerge.rowChunks(layout: ConflictResolverLayout, showBase: Boolean): List<Int> = when (layout) {
    ConflictResolverLayout.COLUMNS -> columnRows(showBase).map { it.chunk }
    ConflictResolverLayout.STACKED -> resultRows(showBase).map { it.chunk }
}

private fun ResolverLists.main(layout: ConflictResolverLayout) = when (layout) {
    ConflictResolverLayout.COLUMNS -> columns
    ConflictResolverLayout.STACKED -> result
}

/** Scrolls [layout]'s lists to [chunk], or to the top without one. The stacked layout's top pair shows it too. */
private suspend fun ResolverLists.show(
    merge: TextMerge,
    layout: ConflictResolverLayout,
    showBase: Boolean,
    chunk: Int?,
    animate: Boolean,
) {
    fun rowAbove(rowChunks: List<Int>) =
        if (chunk == null) 0 else (rowOf(rowChunks, ChunkPlace(chunk, 0)) - CONTEXT_ROWS).coerceAtLeast(0)

    val row = rowAbove(merge.rowChunks(layout, showBase))

    if (layout == ConflictResolverLayout.STACKED) {
        pinned = row to rowAbove(merge.sidesRows().map { it.chunk })
    }

    if (animate) main(layout).animateScrollToItem(row) else main(layout).scrollToItem(row)
}

/** Scrolls [to]'s lists to the place that [from]'s show at their top, mapped by chunk. */
private suspend fun ResolverLists.keepPlace(
    merge: TextMerge,
    showBase: Boolean,
    from: ConflictResolverLayout,
    to: ConflictResolverLayout,
) {
    val fromList = main(from)
    val place = placeOf(merge.rowChunks(from, showBase), fromList.firstVisibleItemIndex) ?: return
    val toChunks = merge.rowChunks(to, showBase)
    val row = rowOf(toChunks, place)
    // The same line in both: the same offset within it
    val offset = if (placeOf(toChunks, row) == place) fromList.firstVisibleItemScrollOffset else 0

    pinned = null
    main(to).scrollToItem(row, offset)
}
