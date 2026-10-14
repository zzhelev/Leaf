// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
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
import dev.app.leaf.domain.conflicts.firstResultLine
import dev.app.leaf.domain.conflicts.placeOf
import dev.app.leaf.domain.conflicts.resultRows
import dev.app.leaf.domain.conflicts.rowOf
import dev.app.leaf.domain.conflicts.sidesRows
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictStage
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
import dev.app.leaf.viewmodels.ResolverHandOff
import dev.app.leaf.viewmodels.ResolverPrompt
import dev.app.leaf.viewmodels.hasChoiceCards
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge as mergeFlows
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The built-in conflict resolver, over the whole tab (`docs/fork/conflict-resolver.md`, steps 2 to 5): the conflicted
 * files on the left, and the one picked in three columns, or with the sides on top of the result, or as a card per
 * side for a conflict that three panes can't show.
 */
@Composable
fun ConflictResolver(viewModel: ConflictResolverViewModel) {
    val state by viewModel.state.collectAsState()
    val lists = remember { ResolverLists() }
    val focusRequester = remember { FocusRequester() }
    val editing = state.edit != null

    // The text field of a part being edited has the focus while it shows, and the view takes it back after, so that
    // the tab's shortcuts and the view's keys still work
    LaunchedEffect(editing) {
        if (!editing) {
            focusRequester.requestFocus()
        }
    }

    ConflictResolverContent(
        state = state,
        lists = lists,
        scrollToChunk = viewModel.scrollToChunk,
        revealChunk = viewModel.revealChunk,
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
            onPick = viewModel::pick,
            onPickBothNames = viewModel::pickBothNames,
            onOpenVersion = viewModel::openVersion,
            onAnswerUnsaved = viewModel::answerUnsavedChanges,
            onReload = viewModel::reload,
            onDismissPrompt = viewModel::dismissPrompt,
            onNextFile = viewModel::nextFile,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            edits = EditActions(onType = viewModel::typeInEdit, onFinish = viewModel::finishEdit),
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
                onEdit = viewModel::startEdit,
                onPutBack = viewModel::putBack,
            ),
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                // While a part is edited, its text field has Enter and the undo keys
                val notEditing = viewModel.state.value.edit == null
                val cards = (viewModel.state.value.file as? ResolverFile.Loaded)?.file?.hasChoiceCards == true
                val arrow = event.type == KeyEventType.KeyDown && !event.isShiftPressed && !event.isCtrlPressed &&
                    !event.isMetaPressed && !event.isAltPressed

                when {
                    cards && arrow && event.key == Key.DirectionLeft -> {
                        viewModel.pick(ConflictSide.OURS)
                        true
                    }

                    cards && arrow && event.key == Key.DirectionRight -> {
                        viewModel.pick(ConflictSide.THEIRS)
                        true
                    }

                    notEditing && isUndoShortcut(event) -> {
                        viewModel.undo()
                        true
                    }

                    notEditing && isRedoShortcut(event) -> {
                        viewModel.redo()
                        true
                    }

                    notEditing && event.matchesBinding(KeybindingOption.SIMPLE_ACCEPT) -> {
                        viewModel.editCurrentConflict()
                        true
                    }

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
    val onAnswerUnsaved: (save: Boolean?) -> Unit,
    val onReload: () -> Unit,
    val onDismissPrompt: () -> Unit,
    /** Opens the next conflicted file, once the file shown was resolved. */
    val onNextFile: () -> Unit,
    val picks: PickActions,
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    /** What the text field of the part being edited does. */
    val edits: EditActions = EditActions(onType = {}, onFinish = {}),
    /** A choice card was picked, for a conflict that three panes can't show. */
    val onPick: (ConflictSide) -> Unit = {},
    /** "Keep both names", for a file that each side renamed differently. */
    val onPickBothNames: () -> Unit = {},
    /** "Open in the default app" on a card: that version, a stage of the index. */
    val onOpenVersion: (ConflictStage) -> Unit = {},
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
    /** Parts (their chunks) that Undo or Redo changed, to scroll to when they're out of sight. */
    revealChunk: Flow<Int> = emptyFlow(),
) {
    val file = state.file as? ResolverFile.Loaded
    // The last file loaded stays while the next one loads, fading out, so that moving on doesn't flash "Loading…"
    val lastLoaded = remember { LastLoaded() }.apply { follow(state.file) }
    val loadingNext = state.file is ResolverFile.Loading && lastLoaded.file != null
    val shownFile = file ?: lastLoaded.file.takeIf { loadingNext }
    val merge = shownFile?.merge?.takeUnless {
        shownFile.workingFileState == WorkingFileState.NO_MARKERS && !shownFile.resolveAgain
    }
    val entry = state.files.firstOrNull { it.entry.filePath == state.file.path }?.entry
    val shown = remember { ShownPlace() }
    // The file shown was resolved: it stays, dimmed and read-only, until the next one is opened
    val handOff = state.handOff?.takeIf { it.from == state.file.path }
    val paused = handOff != null
    // Choice cards dim themselves, all but the button that opens the next file
    val cards = shownFile?.file?.hasChoiceCards == true
    val fade = remember { Animatable(1f) }

    LaunchedEffect(loadingNext, paused, cards) {
        when {
            loadingNext -> fade.animateTo(0f, tween(FADE_OUT_MILLIS))
            paused && !cards -> fade.animateTo(PAUSED_ALPHA, tween(FADE_IN_MILLIS))
            else -> fade.animateTo(1f, tween(FADE_IN_MILLIS))
        }
    }

    // A copy while the file list's edge is dragged; the view model gets the width when the drag ends
    var fileListWidth by remember(state.fileListWidth) { mutableFloatStateOf(state.fileListWidth) }
    val presses = remember { EditorPresses() }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .finishingEditOnPressOutside(state.edit != null, presses, actions.edits.onFinish)
    ) {
        Toolbar(state, file, merge.takeIf { file != null }, entry, paused, actions)

        if (handOff != null) {
            HandOffStrip(handOff)
        }

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
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.takingNoClicks(paused && !cards)) {
                    CompositionLocalProvider(LocalEditorPresses provides presses) {
                        FileView(state, shownFile, merge, lists, shown, scrollToChunk, revealChunk, paused, actions)
                    }
                }

                if (loadingNext) {
                    LoadingCover()
                }
            }
        }
    }
}

/** The file that the view shows, or what it shows in its place, under the toolbar and right of the file list. */
@Composable
private fun FileView(
    state: ConflictResolverState,
    file: ResolverFile.Loaded?,
    merge: TextMerge?,
    lists: ResolverLists,
    shown: ShownPlace,
    scrollToChunk: Flow<Int>,
    revealChunk: Flow<Int>,
    paused: Boolean,
    actions: ResolverActions,
) {
    when {
        state.done -> AllResolved(actions.onClose)
        file == null && state.file is ResolverFile.Loading ->
            Message(stringResource(Res.string.conflict_resolver_loading))

        state.file is ResolverFile.Failed -> Message(state.file.error.getErrorText())
        file == null -> Message(stringResource(Res.string.conflict_resolver_nothing))
        file.file.hasChoiceCards -> ChoiceCards(file.file, file.pick, state.showBase, paused, actions)
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

            // A move to a conflict, or a part that Undo changed. The view model changes the merge first, as when F7
            // finishes editing, so the scroll waits for the rows of that merge
            var scrollRequest by remember { mutableStateOf<ScrollRequest?>(null) }

            LaunchedEffect(scrollToChunk, revealChunk) {
                mergeFlows(
                    scrollToChunk.map { ScrollRequest(it, onlyIfHidden = false) },
                    revealChunk.map { ScrollRequest(it, onlyIfHidden = true) },
                ).collect { scrollRequest = it }
            }

            LaunchedEffect(scrollRequest, layout) {
                // Once: a new layout keeps the place instead. Setting the request back to null would cancel the scroll
                val request = scrollRequest?.takeUnless { it.done } ?: return@LaunchedEffect

                request.done = true

                if (!request.onlyIfHidden || !lists.shows(request.chunk, layout)) {
                    lists.show(currentMerge, layout, showBase, request.chunk, animate = true)
                }
            }

            // Lines that don't wrap scroll sideways together, in both layouts
            val sideways = if (state.wrapLines) null else lists.sideways
            val edit = state.edit
            val editing = edit?.let {
                remember(it, merge) { PartEditing(it, merge.firstResultLine(it.chunk), actions.edits) }
            }

            CompositionLocalProvider(LocalSidewaysScroll provides sideways) {
                LayoutView(merge, file, conflicts, state, lists, actions, editing, !paused, Modifier.weight(1f))
            }

            ResolverLegend(stacked = layout == ConflictResolverLayout.STACKED, editing = edit != null)
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
    editing: PartEditing?,
    canEdit: Boolean,
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
                editing = editing,
                editorPlace = lists.columnsEditor,
                canEdit = canEdit,
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
                editing = editing,
                canEdit = canEdit,
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
    /** The file was resolved: it can't be saved or resolved again, and the strip under the toolbar moves on. */
    paused: Boolean,
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

        if (paused) {
            ResolvedLabel()
        }

        if (state.focused) {
            FileCounter(state.files, state.file.path)
        }

        if (merge != null) {
            ConflictNavigation(merge, state.currentConflict, actions)
        }

        Spacer(Modifier.weight(1f))

        if (merge != null && !paused) {
            UndoButtons(state, actions)
        }

        if (merge != null) {
            LayoutButtons(state.layout, actions.onSetLayout)

            ToggleButton(
                text = stringResource(Res.string.conflict_resolver_wrap),
                tooltip = stringResource(Res.string.conflict_resolver_wrap_tooltip),
                toggled = state.wrapLines,
                onClick = actions.onToggleWrapLines,
            )
        }

        // The cards' base is a card of its own, where it says more than the sides
        val cards = file?.file?.takeIf { merge == null && it.hasChoiceCards }

        if (merge != null || cards != null && showsBaseCard(cards)) {
            ToggleButton(
                text = stringResource(Res.string.conflict_resolver_base),
                tooltip = stringResource(
                    if (cards != null) Res.string.conflict_resolver_base_card_tooltip else Res.string.conflict_resolver_base_tooltip
                ),
                toggled = state.showBase,
                onClick = actions.onToggleBase,
            )
        }

        if (entry?.canOpenInMergeTool == true && file != null && !paused) {
            DelayedTooltip(stringResource(Res.string.conflict_resolver_external_tool_tooltip)) {
                SecondaryButton(
                    text = stringResource(Res.string.conflict_resolver_external_tool),
                    textColor = MaterialTheme.colors.onBackground,
                    backgroundButton = MaterialTheme.colors.secondarySurface,
                    onClick = actions.onOpenInExternalTool,
                )
            }
        }

        // One Next file, where Mark resolved was, so that the pointer stays put: here for text, and on the card picked
        // for choice cards, which have their Mark resolved there too
        val next = state.handOff?.takeIf { paused && cards == null }?.next

        if (next != null) {
            DelayedTooltip(stringResource(Res.string.conflict_resolver_next_file_tooltip, next, acceptShortcut())) {
                PrimaryButton(text = stringResource(Res.string.conflict_resolver_next_file_short), onClick = actions.onNextFile)
            }
        }

        if (merge != null && !paused) {
            DelayedTooltip(stringResource(Res.string.conflict_resolver_save_tooltip)) {
                SecondaryButton(
                    text = stringResource(Res.string.conflict_resolver_save),
                    onClick = actions.onSave,
                )
            }

            // Lines typed in an open conflict resolve it once editing ends, which a click on the button does first
            val edit = state.edit
            val typedConflict = (merge.chunks.getOrNull(edit?.chunk ?: -1) as? TextMerge.Chunk.Conflict)
                ?.takeIf { it.resolution == ConflictResolution.Unresolved && edit?.value?.text != edit?.start?.text }
            val open = merge.openConflicts - if (typedConflict != null) 1 else 0

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

/** Undo and Redo, for the picks and the edits of the file shown. */
@Composable
private fun UndoButtons(state: ConflictResolverState, actions: ResolverActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolbarIcon(
            icon = Res.drawable.undo,
            description = stringResource(Res.string.conflict_resolver_undo_tooltip, undoShortcut()),
            toggled = false,
            enabled = state.canUndo,
            onClick = actions.onUndo,
        )
        ToolbarIcon(
            icon = Res.drawable.redo,
            description = stringResource(Res.string.conflict_resolver_redo_tooltip, redoShortcut()),
            toggled = false,
            enabled = state.canRedo,
            onClick = actions.onRedo,
        )
    }
}

/** An icon button of the toolbar, as the diff pane's `StateIcon`, with the tooltip as the description. */
@Composable
private fun ToolbarIcon(
    icon: DrawableResource,
    description: String,
    toggled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DelayedTooltip(description) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (toggled) MaterialTheme.colors.onSurface.copy(alpha = 0.2f) else Color.Transparent)
                .handMouseClickable(enabled = enabled) { onClick() }
                .semantics { if (!enabled) disabled() }
                .padding(4.dp)
                .size(20.dp),
        )
    }
}

/** Mark resolved's shortcut (`TEXT_ACCEPT`), as the platform writes it: ⌘⏎ on macOS, Ctrl+Enter elsewhere. */
internal fun acceptShortcut() = if (currentOs == OS.MAC) "⌘⏎" else "Ctrl+Enter"

/** Focus's shortcut, as the platform writes it: ⇧⌘F on macOS, Ctrl+Shift+F elsewhere. */
private fun focusShortcut() = if (currentOs == OS.MAC) "⇧⌘F" else "Ctrl+Shift+F"

/** Undo's and Redo's shortcuts, as the platform writes them. */
internal fun undoShortcut() = if (currentOs == OS.MAC) "⌘Z" else "Ctrl+Z"

internal fun redoShortcut() = if (currentOs == OS.MAC) "⇧⌘Z" else "Ctrl+Shift+Z"

/** Undo: Cmd+Z on macOS, Ctrl+Z elsewhere. */
private fun isUndoShortcut(event: KeyEvent) = event.type == KeyEventType.KeyDown && event.key == Key.Z &&
    !event.isShiftPressed && !event.isAltPressed && platformCommand(event)

/** Redo: Shift+Cmd+Z on macOS, Ctrl+Shift+Z or Ctrl+Y elsewhere. */
private fun isRedoShortcut(event: KeyEvent) = event.type == KeyEventType.KeyDown && !event.isAltPressed &&
    platformCommand(event) && (event.key == Key.Z && event.isShiftPressed || currentOs != OS.MAC && event.key == Key.Y)

/** Cmd without Ctrl on macOS, Ctrl without Cmd elsewhere. */
private fun platformCommand(event: KeyEvent) =
    if (currentOs == OS.MAC) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed

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
                        text = file.name(path),
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

                DelayedTooltip(file.tooltip(), modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name(),
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

/** The file's name in the list, and a renamed file's new names after it: `a.txt → b.txt, c.txt`. */
private fun ResolverFileEntry.name(old: String = entry.filePath.substringAfterLast('/')): String =
    if (renamedTo.isEmpty()) old else "$old → ${renamedTo.joinToString(", ") { it.substringAfterLast('/') }}"

/** The file's path, and a renamed file's new paths, one a line. */
private fun ResolverFileEntry.tooltip(): String = (listOf(entry.filePath) + renamedTo).joinToString("\n")

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

/**
 * The file shown was resolved ([handOff]): the strip says so, and opens the next one, as Cmd+Enter (Mark resolved's
 * shortcut) does again.
 */
@Composable
private fun HandOffStrip(handOff: ResolverHandOff) {
    val resolved = if (handOff.resolvedHere) {
        stringResource(Res.string.conflict_resolver_hand_off_resolved, handOff.from)
    } else {
        stringResource(Res.string.conflict_resolver_hand_off_elsewhere, handOff.from)
    }
    // The button is where Mark resolved was, in the toolbar or the card picked: the strip only says what it opens
    val next = stringResource(Res.string.conflict_resolver_hand_off_next, acceptShortcut(), handOff.next)

    Strip(text = "$resolved $next", color = MaterialTheme.colors.addFile, icon = Res.drawable.check) {}
}

/** After the file name, while the file shown is resolved and the view waits to open the next one. */
@Composable
private fun ResolvedLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painter = painterResource(Res.drawable.check),
            contentDescription = null,
            tint = MaterialTheme.colors.addFile,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(Res.string.conflict_resolver_resolved),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.addFile,
            maxLines = 1,
        )
    }
}

@Composable
private fun Strip(text: String, color: Color, icon: DrawableResource? = null, buttons: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }

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
 * In Focus, next to the file menu: how far through the conflicted files the view is, as "2 of 3 files". The files
 * resolved here come first, so [current] is the last of them once it's resolved, and the one after them before.
 */
@Composable
private fun FileCounter(files: List<ResolverFileEntry>, current: String?) {
    if (files.size < 2) {
        return
    }

    val resolved = files.count { it.resolvedHere }
    val currentResolved = files.any { it.resolvedHere && it.entry.filePath == current }
    val number = (if (currentResolved) resolved else resolved + 1).coerceAtMost(files.size)

    Text(
        text = stringResource(Res.string.conflict_resolver_file_counter, number, files.size),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
    )
}

/**
 * Over the last file while the next one loads: it takes the pointer, as the picks would go nowhere, and says
 * "Loading…" once the load takes long enough to notice.
 */
@Composable
private fun LoadingCover() {
    var slow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(SLOW_LOAD_MILLIS)
        slow = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        if (slow) {
            Message(stringResource(Res.string.conflict_resolver_loading))
        }
    }
}

/**
 * While [paused], the file takes no clicks, as it was written and staged, but still scrolls: presses stop here, before
 * the arrows and chips under it see them, and the wheel goes through.
 */
private fun Modifier.takingNoClicks(paused: Boolean): Modifier {
    if (!paused) {
        return this
    }

    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)

                if (event.type == PointerEventType.Press) {
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

/** The last file loaded: kept while the next one loads ([ResolverFile.Loading]), and dropped by anything else. */
private class LastLoaded {
    var file: ResolverFile.Loaded? = null
        private set

    fun follow(current: ResolverFile) {
        when (current) {
            is ResolverFile.Loaded -> file = current
            is ResolverFile.Loading -> Unit
            else -> file = null
        }
    }
}

/** How long the last file takes to fade out when the next one starts loading, and the next one to fade in. */
private const val FADE_OUT_MILLIS = 90
private const val FADE_IN_MILLIS = 210

/** How much a resolved file shows while the view waits to open the next one. */
private const val PAUSED_ALPHA = 0.35f

/** A load that takes longer than this says "Loading…" over the faded file. */
private const val SLOW_LOAD_MILLIS = 400L

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

/** A part (its chunk) to scroll to, always or [onlyIfHidden], once. */
private class ScrollRequest(val chunk: Int, val onlyIfHidden: Boolean) {
    var done = false
}

/** Whether [layout]'s main list shows a row of [chunk]. */
private fun ResolverLists.shows(chunk: Int, layout: ConflictResolverLayout): Boolean =
    main(layout).layoutInfo.visibleItemsInfo.any { (it.key as? Long)?.shr(32)?.toInt() == chunk }

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
