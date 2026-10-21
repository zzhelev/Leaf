// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import dev.app.leaf.ui.components.SidewaysScroll
import dev.app.leaf.ui.components.LocalSidewaysScroll
import dev.app.leaf.ui.components.scrollsSideways
import dev.app.leaf.ui.components.SidewaysScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.conflicts.CellKind
import dev.app.leaf.domain.conflicts.ChangedWords
import dev.app.leaf.domain.conflicts.ConflictArrow
import dev.app.leaf.domain.conflicts.ResultRow
import dev.app.leaf.domain.conflicts.SidesRow
import dev.app.leaf.domain.conflicts.arrowFor
import dev.app.leaf.domain.conflicts.placeOf
import dev.app.leaf.domain.conflicts.rowOf
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.SECTION_DIVIDER_HEIGHT
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.toStringWithSpaces
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.SectionDivider
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.diff.LineNumber
import dev.app.leaf.ui.diff.syntax_highlighter.SyntaxHighlighter
import dev.app.leaf.ui.diff.syntax_highlighter.getSyntaxHighlighterFromExtension
import dev.app.leaf.ui.status.label
import dev.app.leaf.viewmodels.DEFAULT_STACKED_SPLIT
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The top pair keeps its headers and a few lines, and the result a few more. */
private val MinSidesHeight = 90.dp
private val MinResultHeight = 120.dp

/**
 * The lists of both layouts. They stay while the view is open, so that switching the layout keeps the place in the
 * file.
 */
@Stable
class ResolverLists {
    val columns = LazyListState()
    val sides = LazyListState()
    val result = LazyListState()

    /** What keeps the columns, and the stacked layout's result, still while a part is edited. */
    val columnsEditor = EditorPlace(columns)
    val resultEditor = EditorPlace(result)

    /** Where the lines are scrolled to sideways while they don't wrap, in both layouts. */
    val sideways = SidewaysScroll()

    /**
     * A row of the result and the row of the top pair that goes with it, set when the view shows a conflict: while the
     * result's top is at that row, the top pair shows the conflict rather than the place at the result's top. That
     * place can be in a chunk that is taller on top, such as lines one side deleted. It goes once the result has been
     * there and moved on.
     */
    var pinned: Pair<Int, Int>? = null
        set(value) {
            field = value
            pinReached = false
        }

    internal var pinReached = false
}

/**
 * Ours and theirs side by side on top, and the result below at the full width (`conflict-resolver.md`, Option B), with
 * a handle between them that keeps its place until Leaf quits. The top pair follows the result, chunk by chunk. A
 * chunk's first row on top ends with chips in place of the columns' arrows: Take ↓, then Add ↓ once the other side is
 * taken, and Taken once that side's lines are in the result, which takes them out again; a change one side made is
 * Applied, which undoes it, or can be applied again.
 *
 * A part of the result being edited ([editing]) shows as a text field in place of its rows, at the full width. While
 * [canEdit], a double-click on the result, or its Edit button, edits a part.
 */
@Composable
fun ResolverStackedView(
    merge: TextMerge,
    sidesRows: List<SidesRow>,
    resultRows: List<ResultRow>,
    sides: ConflictSides?,
    filePath: String,
    currentConflictChunk: Int?,
    showBase: Boolean,
    /** The top pair's share of the height. */
    split: Float,
    lists: ResolverLists,
    actions: PickActions,
    onSplitChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    editing: PartEditing? = null,
    canEdit: Boolean = true,
) {
    val syntaxHighlighter = remember(filePath) { getSyntaxHighlighterFromExtension(filePath.substringAfterLast('.')) }
    // Picks and lines typed keep the chunks' lines, so the words marked are kept until another file loads
    val words = remember(merge.base, merge.ours, merge.theirs) { ChangedWords(merge) }
    val numberWidth = remember(merge) {
        digits(maxOf(merge.ours.size(), merge.theirs.size(), merge.base.size() + merge.ours.size()))
    }
    val conflictNumbers = remember(merge) {
        merge.chunks.indices.filter { merge.chunks[it] is Chunk.Conflict }.withIndex().associate { it.value to it.index + 1 }
    }

    val editChunk = editing?.edit?.chunk
    val editorPlace = lists.resultEditor

    if (editChunk != null) editorPlace.start(editChunk) else editorPlace.end()

    val anchorRow = editorPlace.anchorRow
    val resultItems = remember(resultRows, editChunk, anchorRow) {
        listItems(resultRows, { it.chunk }, editChunk, anchorRow)
    }

    // The top pair follows the place at the result's top. On a line that both show, it keeps the result's offset in
    // it. A request, applied at the next layout: scrollToItem lays out at once, which throws during a layout
    LaunchedEffect(sidesRows, resultItems) {
        val resultChunks = resultItems.map { it.chunk }
        val sideChunks = sidesRows.map { it.chunk }

        snapshotFlow { lists.result.firstVisibleItemIndex to lists.result.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val pinned = lists.pinned

                if (pinned != null && pinned.first == index) {
                    lists.pinReached = true
                    lists.sides.requestScrollToItem(pinned.second)
                    return@collect
                }

                // Before the result gets there, the place at its top is followed, and the pin is kept
                if (pinned != null && lists.pinReached) {
                    lists.pinned = null
                }

                val place = placeOf(resultChunks, index) ?: return@collect
                val row = rowOf(sideChunks, place)
                val line = resultItems.getOrNull(index) as? ListItem.Line
                val sameLine = placeOf(sideChunks, row) == place && line?.row?.cell?.text != null

                lists.sides.requestScrollToItem(row, if (sameLine) offset else 0)
            }
    }

    BoxWithConstraints(modifier) {
        val available = (maxHeight - SECTION_DIVIDER_HEIGHT.dp).value.coerceAtLeast(1f)
        val minShare = (MinSidesHeight.value / available).coerceAtMost(1f)
        val maxShare = ((available - MinResultHeight.value) / available).coerceAtLeast(minShare)
        // A copy while the handle is dragged; the view model gets the share when the drag ends
        var share by remember(split) { mutableFloatStateOf(split) }
        val sideways = LocalSidewaysScroll.current

        // Ours and theirs share the width on top, so they're the narrowest columns
        FitSideways(sideways, merge, narrowestColumn = (maxWidth - 1.dp) / 2, numberDigits = numberWidth)

        Column(Modifier.fillMaxSize().scrollsSideways(sideways)) {
            Column(Modifier.height((available * share.coerceIn(minShare, maxShare)).dp)) {
                SidesHeaders(sides)

                ScrollableLazyColumn(modifier = Modifier.fillMaxSize(), state = lists.sides) {
                    itemsIndexed(sidesRows, key = { index, _ -> index }) { _, row ->
                        SidesRowView(
                            row = row,
                            chunk = merge.chunks[row.chunk],
                            sides = sides,
                            numberWidth = numberWidth,
                            isCurrentConflict = row.chunk == currentConflictChunk,
                            syntaxHighlighter = syntaxHighlighter,
                            words = words,
                            actions = actions,
                        )
                    }
                }
            }

            SectionDivider(
                onDrag = { delta ->
                    share = (share.coerceIn(minShare, maxShare) + delta / available).coerceIn(minShare, maxShare)
                },
                onDragStopped = { onSplitChanged(share.coerceIn(minShare, maxShare)) },
                onDoubleClick = {
                    share = DEFAULT_STACKED_SPLIT
                    onSplitChanged(DEFAULT_STACKED_SPLIT)
                },
            )

            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(30.dp).background(MaterialTheme.colors.tertiarySurface),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Header(
                        title = stringResource(Res.string.conflict_resolver_result),
                        name = null,
                        tooltip = stringResource(Res.string.conflict_resolver_result_tooltip),
                        color = MaterialTheme.colors.onBackground,
                    )
                }

                editorPlace.Restore(resultItems)

                ScrollableLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), state = lists.result) {
                    items(resultItems, key = { it.key }) { item ->
                        when (item) {
                            is ListItem.Line -> ResultRowView(
                                row = item.row,
                                rowInChunk = item.rowInChunk,
                                chunk = merge.chunks[item.chunk],
                                sides = sides,
                                numberWidth = numberWidth,
                                conflictNumber = conflictNumbers[item.chunk],
                                isCurrentConflict = item.chunk == currentConflictChunk,
                                showBase = showBase,
                                syntaxHighlighter = syntaxHighlighter,
                                words = words,
                                actions = actions,
                                canEdit = canEdit,
                            )

                            is ListItem.Editor -> if (editing != null) {
                                val isCurrent = item.chunk == currentConflictChunk

                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                    Box(
                                        Modifier
                                            .width(if (isCurrent) 6.dp else 4.dp)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colors.primaryVariant)
                                    )
                                    PartEditor(editing, numberWidth, editorPlace, syntaxHighlighter, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                SidewaysScrollbar(sideways)
            }
        }
    }
}

@Composable
private fun SidesHeaders(sides: ConflictSides?) {
    val explanation = sides?.explanation()

    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).background(MaterialTheme.colors.tertiarySurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Header(
            title = stringResource(Res.string.conflict_resolver_ours),
            name = sides?.label(ConflictSide.OURS),
            tooltip = explanation,
            color = MaterialTheme.colors.primary,
        )
        Header(
            title = stringResource(Res.string.conflict_resolver_theirs),
            name = sides?.label(ConflictSide.THEIRS),
            tooltip = explanation,
            color = TheirsColor,
        )
    }
}

@Composable
private fun SidesRowView(
    row: SidesRow,
    chunk: Chunk,
    sides: ConflictSides?,
    numberWidth: Int,
    isCurrentConflict: Boolean,
    syntaxHighlighter: SyntaxHighlighter,
    words: ChangedWords,
    actions: PickActions,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        for (side in ConflictSide.entries) {
            val cell = if (side == ConflictSide.OURS) row.ours else row.theirs

            Cell(
                cell = cell,
                numberWidth = numberWidth,
                isCurrentConflict = isCurrentConflict,
                syntaxHighlighter = syntaxHighlighter,
                words = words,
                modifier = Modifier.weight(1f),
                chip = if (row.first) {
                    { SideChip(row.chunk, chunk, side, sides, actions) }
                } else {
                    null
                },
            )

            if (side == ConflictSide.OURS) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colors.onBackground.copy(alpha = 0.12f)))
            }
        }
    }
}

/** What a chunk's first row on top offers for [side]: see [ResolverStackedView]. */
@Composable
private fun SideChip(chunkIndex: Int, chunk: Chunk, side: ConflictSide, sides: ConflictSides?, actions: PickActions) {
    val sideName = sides?.label(side) ?: side.name.lowercase()

    when (chunk) {
        is Chunk.Unchanged -> Unit

        is Chunk.Change -> {
            // Ours' and both sides' changes on the left, theirs' on the right, as the columns' gutters have them
            val onThisSide = if (side == ConflictSide.OURS) chunk.by != ChangedBy.THEIRS else chunk.by == ChangedBy.THEIRS

            if (!onThisSide) {
                return
            }

            if (chunk.edited != null) {
                // In place of the lines typed
                Chip(
                    text = stringResource(Res.string.conflict_resolver_chip_apply),
                    tooltip = stringResource(Res.string.conflict_resolver_change_instead),
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    onClick = { actions.onSetApplied(chunkIndex, true) },
                )
            } else if (chunk.applied) {
                Chip(
                    text = stringResource(Res.string.conflict_resolver_chip_applied),
                    tooltip = stringResource(Res.string.conflict_resolver_undo_change),
                    color = MaterialTheme.colors.addFile,
                    checked = true,
                    onClick = { actions.onSetApplied(chunkIndex, false) },
                )
            } else {
                Chip(
                    text = stringResource(Res.string.conflict_resolver_chip_apply),
                    tooltip = stringResource(Res.string.conflict_resolver_apply_change),
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    onClick = { actions.onSetApplied(chunkIndex, true) },
                )
            }
        }

        is Chunk.Conflict -> {
            val ours = side == ConflictSide.OURS

            when (chunk.resolution.arrowFor(side)) {
                ConflictArrow.TAKE -> Chip(
                    text = stringResource(Res.string.conflict_resolver_chip_take),
                    tooltip = stringResource(
                        if (ours) Res.string.conflict_resolver_take_ours else Res.string.conflict_resolver_take_theirs,
                        sideName,
                    ),
                    color = MaterialTheme.colors.conflictFile,
                    filled = true,
                    onClick = { actions.onTake(chunkIndex, side) },
                )

                ConflictArrow.ADD_AFTER -> Chip(
                    text = stringResource(Res.string.conflict_resolver_chip_add),
                    tooltip = stringResource(
                        if (ours) Res.string.conflict_resolver_add_ours else Res.string.conflict_resolver_add_theirs,
                        sideName,
                    ),
                    color = MaterialTheme.colors.conflictFile,
                    filled = true,
                    onClick = { actions.onTake(chunkIndex, side) },
                )

                null -> Chip(
                    text = stringResource(Res.string.conflict_resolver_chip_taken),
                    tooltip = stringResource(
                        if (ours) Res.string.conflict_resolver_drop_ours else Res.string.conflict_resolver_drop_theirs,
                        sideName,
                    ),
                    color = MaterialTheme.colors.addFile,
                    checked = true,
                    onClick = { actions.onDrop(chunkIndex, side) },
                )
            }
        }
    }
}

/**
 * A chip at the end of a row: [filled] for a conflict's Take and Add, the main thing to click, as the columns' arrows,
 * and [checked] for what the result already has.
 */
@Composable
private fun Chip(
    text: String,
    tooltip: String,
    color: Color,
    filled: Boolean = false,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (filled) OnArrowColor else color

    DelayedTooltip(tooltip) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (filled) color else Color.Transparent)
                .border(1.dp, color, RoundedCornerShape(4.dp))
                .handMouseClickable { onClick() }
                .padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                Icon(
                    painter = painterResource(Res.drawable.check),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.padding(end = 3.dp).size(12.dp),
                )
            }

            Text(
                text = text,
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * A row of the result: a line with a bar on its left in the colour of where it comes from, an open conflict's
 * placeholder (or its base's lines, with Base), or a thin mark where a chunk has no line in the result.
 */
@Composable
private fun ResultRowView(
    row: ResultRow,
    rowInChunk: Int,
    chunk: Chunk,
    sides: ConflictSides?,
    numberWidth: Int,
    conflictNumber: Int?,
    isCurrentConflict: Boolean,
    showBase: Boolean,
    syntaxHighlighter: SyntaxHighlighter,
    words: ChangedWords,
    actions: PickActions,
    canEdit: Boolean,
) {
    val cell = row.cell
    val text = cell.text
    val barColor = cell.kind.barColor()

    val content = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(cell.kind.background())
        ) {
            // The current conflict's bar is wider, as the columns mark its cells
            val barWidth = if (isCurrentConflict && chunk is Chunk.Conflict) 6.dp else 4.dp

            Box(Modifier.width(barWidth).fillMaxHeight().background(barColor))

            when {
                cell.kind == CellKind.OPEN_CONFLICT -> Box(
                    Modifier.weight(1f).fillMaxHeight().hatched(MaterialTheme.colors.conflictFile.copy(alpha = 0.25f))
                ) {
                    Column {
                        if (row.first && conflictNumber != null) {
                            Text(
                                text = stringResource(Res.string.conflict_resolver_open_conflict_stacked, conflictNumber),
                                style = MaterialTheme.typography.caption,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colors.conflictFile,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }

                        if (text != null && showBase) {
                            Row(Modifier.alpha(0.6f)) {
                                LineNumber(text = " ".repeat(numberWidth), remarked = false)
                                LineText(text.getString(cell.line), syntaxHighlighter)
                            }
                        }
                    }
                }

                // Lines deleted, or a change that isn't applied and adds lines: a line across, where they were
                text == null -> Box(Modifier.weight(1f).height(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(barColor))
                }

                else -> {
                    LineNumber(
                        text = cell.number.toStringWithSpaces(numberWidth),
                        remarked = cell.kind != CellKind.UNCHANGED,
                    )
                    LineText(text.getString(cell.line), syntaxHighlighter, marksOf(cell, words))
                }
            }
        }
    }

    ContextMenu(items = { resultMenu(row.chunk, chunk, sides, actions, canEdit) }) {
        EditableRow(
            chunk = row.chunk,
            line = rowInChunk,
            first = row.first,
            edited = cell.edited,
            canEdit = canEdit,
            onEdit = actions.onEdit,
        ) {
            content()
        }
    }
}

/** The bar on the left of the result's lines: where they come from. */
@Composable
private fun CellKind.barColor(): Color = when (this) {
    CellKind.UNCHANGED -> Color.Transparent
    CellKind.CHANGED_BY_OURS -> MaterialTheme.colors.primary
    CellKind.CHANGED_BY_THEIRS -> TheirsColor
    CellKind.CHANGED_BY_BOTH -> MaterialTheme.colors.onBackground.copy(alpha = 0.3f)
    CellKind.CONFLICT, CellKind.OPEN_CONFLICT -> MaterialTheme.colors.conflictFile
    CellKind.RESOLVED -> MaterialTheme.colors.addFile
    CellKind.EDITED -> MaterialTheme.colors.onBackground.copy(alpha = 0.45f)
}
