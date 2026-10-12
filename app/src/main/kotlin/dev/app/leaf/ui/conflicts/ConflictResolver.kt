// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.conflicts.columnRows
import dev.app.leaf.domain.conflicts.conflictChunks
import dev.app.leaf.domain.models.ConflictContent
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
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The built-in conflict resolver, over the whole tab (`docs/fork/conflict-resolver.md`, step 2): the conflicted files
 * on the left, and the one picked in three columns. [onConfirmUseSide] asks before a side is taken for a whole file,
 * as "Use ours" and "Use theirs" do elsewhere.
 */
@Composable
fun ConflictResolver(
    viewModel: ConflictResolverViewModel,
    onConfirmUseSide: (StatusEntry, ConflictSide, ConflictSides?, onConfirm: () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    ConflictResolverContent(
        state = state,
        listState = listState,
        actions = ResolverActions(
            onClose = viewModel::close,
            onSelectFile = viewModel::selectFile,
            onPreviousConflict = viewModel::previousConflict,
            onNextConflict = viewModel::nextConflict,
            onToggleBase = viewModel::toggleBase,
            onSave = { viewModel.save() },
            onMarkResolved = { viewModel.markResolved() },
            onOpenInExternalTool = viewModel::openInExternalTool,
            onMarkResolvedAsItIs = viewModel::markResolvedAsItIs,
            onResolveAgain = viewModel::resolveAgain,
            onUseSide = { entry, side, sides -> onConfirmUseSide(entry, side, sides) { viewModel.useSide(side) } },
            onAnswerUnsaved = viewModel::answerUnsavedChanges,
            onReload = viewModel::reload,
            onDismissPrompt = viewModel::dismissPrompt,
            columns = ColumnActions(
                onTake = { chunk, side ->
                    viewModel.take(chunk, side)
                    viewModel.selectConflict(chunk)
                },
                onResolve = { chunk, resolution ->
                    viewModel.resolve(chunk, resolution)
                    viewModel.selectConflict(chunk)
                },
                onSetApplied = viewModel::setApplied,
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

                    else -> false
                }
            },
    )

    LaunchedEffect(viewModel) {
        viewModel.scrollToChunk.collect { chunk ->
            val merge = (viewModel.state.value.file as? ResolverFile.Loaded)?.merge ?: return@collect
            val row = merge.columnRows(viewModel.state.value.showBase).indexOfFirst { it.chunk == chunk }

            if (row >= 0) {
                // A few rows of context above the conflict
                listState.animateScrollToItem((row - 3).coerceAtLeast(0))
            }
        }
    }
}

/** What the resolver's buttons do, apart from the columns'. */
class ResolverActions(
    val onClose: () -> Unit,
    val onSelectFile: (String) -> Unit,
    val onPreviousConflict: () -> Unit,
    val onNextConflict: () -> Unit,
    val onToggleBase: () -> Unit,
    val onSave: () -> Unit,
    val onMarkResolved: () -> Unit,
    val onOpenInExternalTool: () -> Unit,
    val onMarkResolvedAsItIs: () -> Unit,
    val onResolveAgain: () -> Unit,
    val onUseSide: (StatusEntry, ConflictSide, ConflictSides?) -> Unit,
    val onAnswerUnsaved: (save: Boolean?) -> Unit,
    val onReload: () -> Unit,
    val onDismissPrompt: () -> Unit,
    val columns: ColumnActions,
)

/** The resolver as [state] shows it, without its view model, so that tests can render it. */
@Composable
fun ConflictResolverContent(
    state: ConflictResolverState,
    listState: LazyListState,
    actions: ResolverActions,
    modifier: Modifier = Modifier,
) {
    val file = state.file as? ResolverFile.Loaded
    val merge = file?.merge?.takeUnless { file.workingFileState == WorkingFileState.NO_MARKERS && !file.resolveAgain }
    val entry = state.files.firstOrNull { it.entry.filePath == state.file.path }?.entry

    Column(modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Toolbar(state, file, merge, entry, actions)

        state.prompt?.let { Prompt(it, state.file.path.orEmpty(), actions) }

        Row(Modifier.weight(1f)) {
            FileList(state.files, state.file.path, actions.onSelectFile)

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
                        val rows = remember(merge, state.showBase) { merge.columnRows(state.showBase) }

                        // Opens at the first conflict
                        LaunchedEffect(file.file.path) {
                            val first = rows.indexOfFirst { it.chunk == conflicts.firstOrNull() }
                            listState.scrollToItem((first - 3).coerceAtLeast(0))
                        }

                        ResolverColumnsView(
                            merge = merge,
                            rows = rows,
                            sides = file.file.sides,
                            filePath = file.file.path,
                            currentConflictChunk = conflicts.getOrNull(state.currentConflict),
                            showBase = state.showBase,
                            listState = listState,
                            actions = actions.columns,
                            modifier = Modifier.weight(1f),
                        )

                        ResolverLegend()
                    }
                }
            }
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

        Text(
            text = state.file.path.orEmpty(),
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp),
        )

        if (merge != null) {
            ConflictNavigation(merge, state.currentConflict, actions)
        }

        Spacer(Modifier.weight(1f))

        if (merge != null) {
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
private fun FileList(files: List<ResolverFileEntry>, current: String?, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .width(220.dp)
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
