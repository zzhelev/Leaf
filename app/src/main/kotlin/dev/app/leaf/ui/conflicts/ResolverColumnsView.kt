// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.conflicts.CellKind
import dev.app.leaf.domain.conflicts.ColumnCell
import dev.app.leaf.domain.conflicts.ColumnRow
import dev.app.leaf.domain.conflicts.ConflictArrow
import dev.app.leaf.domain.conflicts.arrowFor
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.toStringWithSpaces
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.addContextMenu
import dev.app.leaf.ui.diff.DiffText
import dev.app.leaf.ui.diff.LineNumber
import dev.app.leaf.ui.diff.syntax_highlighter.getSyntaxHighlighterFromExtension
import dev.app.leaf.ui.status.label
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.log10

/** Changes from theirs. The theme has no colour of its own for them; it reads on both the light and the dark theme. */
private val TheirsColor = Color(0xFF9C6ADE)

private val GutterWidth = 40.dp

/** Dark, on the amber of the conflict colour, which is the same in the light and the dark theme. */
private val OnArrowColor = Color(0xFF1E1E1E)

/** What the columns' gutters and conflict menus do. */
class ColumnActions(
    val onTake: (chunk: Int, side: ConflictSide) -> Unit,
    val onResolve: (chunk: Int, resolution: ConflictResolution) -> Unit,
    val onSetApplied: (chunk: Int, applied: Boolean) -> Unit,
)

/**
 * Ours, the result and theirs side by side (`conflict-resolver.md`, Option A), as rows of one list so that they scroll
 * together. A chunk's gutters have its buttons on its first row: an applied change can be undone (×) or applied again
 * (✓), and a conflict's arrows point into the result, ▶ taking ours and ◀ taking theirs.
 */
@Composable
fun ResolverColumnsView(
    merge: TextMerge,
    rows: List<ColumnRow>,
    sides: ConflictSides?,
    filePath: String,
    currentConflictChunk: Int?,
    showBase: Boolean,
    listState: LazyListState,
    actions: ColumnActions,
    modifier: Modifier = Modifier,
) {
    val syntaxHighlighter = remember(filePath) { getSyntaxHighlighterFromExtension(filePath.substringAfterLast('.')) }
    val numberWidth = remember(merge) {
        digits(maxOf(merge.ours.size(), merge.theirs.size(), merge.base.size() + merge.ours.size()))
    }
    val conflictNumbers = remember(merge) {
        merge.chunks.indices.filter { merge.chunks[it] is Chunk.Conflict }.withIndex().associate { it.value to it.index + 1 }
    }

    Column(modifier) {
        ColumnHeaders(sides)

        ScrollableLazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(rows, key = { index, _ -> index }) { _, row ->
                ResolverRow(
                    row = row,
                    chunk = merge.chunks[row.chunk],
                    sides = sides,
                    numberWidth = numberWidth,
                    conflictNumber = conflictNumbers[row.chunk],
                    isCurrentConflict = row.chunk == currentConflictChunk,
                    showBase = showBase,
                    syntaxHighlighter = syntaxHighlighter,
                    actions = actions,
                )
            }
        }
    }
}

private fun digits(count: Int) = if (count <= 0) 1 else log10(count.toDouble()).toInt() + 1

@Composable
private fun ColumnHeaders(sides: ConflictSides?) {
    val explanation = sides?.explanation()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(MaterialTheme.colors.tertiarySurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Header(
            title = stringResource(Res.string.conflict_resolver_ours),
            name = sides?.label(ConflictSide.OURS),
            tooltip = explanation,
            color = MaterialTheme.colors.primary,
        )
        Spacer(Modifier.width(GutterWidth))
        Header(
            title = stringResource(Res.string.conflict_resolver_result),
            name = null,
            tooltip = stringResource(Res.string.conflict_resolver_result_tooltip),
            color = MaterialTheme.colors.onBackground,
        )
        Spacer(Modifier.width(GutterWidth))
        Header(
            title = stringResource(Res.string.conflict_resolver_theirs),
            name = sides?.label(ConflictSide.THEIRS),
            tooltip = explanation,
            color = TheirsColor,
        )
    }
}

@Composable
private fun RowScope.Header(title: String, name: String?, tooltip: String?, color: Color) {
    DelayedTooltip(tooltip, modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Text(
                text = if (name != null) "$title: $name" else title,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** What ours and theirs mean in the operation in progress, as few people remember it. */
@Composable
fun ConflictSides.explanation(): String {
    val ours = label(ConflictSide.OURS) ?: stringResource(Res.string.conflict_side_ours)
    val theirs = label(ConflictSide.THEIRS) ?: stringResource(Res.string.conflict_side_theirs)

    return when (operation) {
        ConflictOperation.MERGE -> stringResource(Res.string.conflict_resolver_sides_merge, ours, theirs)
        ConflictOperation.REBASE -> stringResource(Res.string.conflict_resolver_sides_rebase, ours, theirs)
        ConflictOperation.CHERRY_PICK -> stringResource(Res.string.conflict_resolver_sides_cherry_pick, ours, theirs)
        ConflictOperation.REVERT -> stringResource(Res.string.conflict_resolver_sides_revert, ours, theirs)
        ConflictOperation.OTHER -> stringResource(Res.string.conflict_resolver_sides_other, ours, theirs)
    }
}

@Composable
private fun ResolverRow(
    row: ColumnRow,
    chunk: Chunk,
    sides: ConflictSides?,
    numberWidth: Int,
    conflictNumber: Int?,
    isCurrentConflict: Boolean,
    showBase: Boolean,
    syntaxHighlighter: dev.app.leaf.ui.diff.syntax_highlighter.SyntaxHighlighter,
    actions: ColumnActions,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Cell(row.ours, numberWidth, isCurrentConflict, syntaxHighlighter, Modifier.weight(1f))
        Gutter(row, chunk, ConflictSide.OURS, sides, actions)

        val result = @Composable {
            ResultCell(row, numberWidth, conflictNumber, isCurrentConflict, showBase, syntaxHighlighter)
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (chunk is Chunk.Conflict) {
                ContextMenu(items = { conflictMenu(row.chunk, sides, actions) }) { result() }
            } else {
                result()
            }
        }

        Gutter(row, chunk, ConflictSide.THEIRS, sides, actions)
        Cell(row.theirs, numberWidth, isCurrentConflict, syntaxHighlighter, Modifier.weight(1f))
    }
}

@Composable
private fun Cell(
    cell: ColumnCell,
    numberWidth: Int,
    isCurrentConflict: Boolean,
    syntaxHighlighter: dev.app.leaf.ui.diff.syntax_highlighter.SyntaxHighlighter,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(cell.kind.background())
            .currentConflictMark(isCurrentConflict && cell.kind == CellKind.CONFLICT)
    ) {
        LineNumber(
            text = if (cell.number > 0) cell.number.toStringWithSpaces(numberWidth) else " ".repeat(numberWidth),
            remarked = cell.kind != CellKind.UNCHANGED,
        )

        val text = cell.text

        if (text == null) {
            Box(Modifier.fillMaxSize().hatched(MaterialTheme.colors.onBackground.copy(alpha = 0.08f)))
        } else {
            DiffText(text.getString(cell.line), matchLine = null, syntaxHighlighter = syntaxHighlighter)
        }
    }
}

@Composable
private fun ResultCell(
    row: ColumnRow,
    numberWidth: Int,
    conflictNumber: Int?,
    isCurrentConflict: Boolean,
    showBase: Boolean,
    syntaxHighlighter: dev.app.leaf.ui.diff.syntax_highlighter.SyntaxHighlighter,
) {
    val cell = row.result

    if (cell.kind != CellKind.OPEN_CONFLICT) {
        Cell(cell, numberWidth, isCurrentConflict, syntaxHighlighter)
        return
    }

    // An open conflict: a hatched placeholder, or the base's lines, faded, with Base on
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cell.kind.background())
            .hatched(MaterialTheme.colors.conflictFile.copy(alpha = 0.25f))
            .currentConflictMark(isCurrentConflict),
    ) {
        val text = cell.text

        Column {
            if (row.first && conflictNumber != null) {
                Text(
                    text = stringResource(Res.string.conflict_resolver_open_conflict, conflictNumber),
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colors.conflictFile,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }

            if (text != null && showBase) {
                Row(Modifier.alpha(0.6f)) {
                    LineNumber(text = " ".repeat(numberWidth), remarked = false)
                    DiffText(text.getString(cell.line), matchLine = null, syntaxHighlighter = syntaxHighlighter)
                }
            }
        }
    }
}

/** A gutter next to the result: the buttons of [chunk] for [side]'s column, on its first row. */
@Composable
private fun Gutter(row: ColumnRow, chunk: Chunk, side: ConflictSide, sides: ConflictSides?, actions: ColumnActions) {
    Column(
        modifier = Modifier.width(GutterWidth).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!row.first) {
            return@Column
        }

        val sideName = sides?.label(side)

        when (chunk) {
            is Chunk.Unchanged -> Unit

            is Chunk.Change -> {
                // Ours' and both sides' changes on the left, theirs' on the right
                val onThisSide = if (side == ConflictSide.OURS) chunk.by != ChangedBy.THEIRS else chunk.by == ChangedBy.THEIRS

                if (onThisSide) {
                    if (chunk.applied) {
                        GutterButton(
                            painter = painterResource(Res.drawable.close),
                            tooltip = stringResource(Res.string.conflict_resolver_undo_change),
                            color = MaterialTheme.colors.onBackgroundSecondary,
                            onClick = { actions.onSetApplied(row.chunk, false) },
                        )
                    } else {
                        GutterButton(
                            painter = painterResource(Res.drawable.check),
                            tooltip = stringResource(Res.string.conflict_resolver_apply_change),
                            color = MaterialTheme.colors.addFile,
                            onClick = { actions.onSetApplied(row.chunk, true) },
                        )
                    }
                }
            }

            is Chunk.Conflict -> {
                val (take, addAfter) = when (side) {
                    ConflictSide.OURS -> Res.string.conflict_resolver_take_ours to Res.string.conflict_resolver_add_ours
                    ConflictSide.THEIRS -> Res.string.conflict_resolver_take_theirs to Res.string.conflict_resolver_add_theirs
                }
                val arrowIcon = if (side == ConflictSide.OURS) Res.drawable.arrow_forward else Res.drawable.arrow_back

                when (chunk.resolution.arrowFor(side)) {
                    ConflictArrow.TAKE -> GutterButton(
                        painter = painterResource(arrowIcon),
                        tooltip = stringResource(take, sideName ?: side.name.lowercase()),
                        color = MaterialTheme.colors.conflictFile,
                        filled = true,
                        onClick = { actions.onTake(row.chunk, side) },
                    )

                    ConflictArrow.ADD_AFTER -> GutterButton(
                        painter = painterResource(Res.drawable.add),
                        tooltip = stringResource(addAfter, sideName ?: side.name.lowercase()),
                        color = MaterialTheme.colors.conflictFile,
                        filled = true,
                        onClick = { actions.onTake(row.chunk, side) },
                    )

                    null -> Unit
                }

                // The left gutter also opens a resolved conflict again
                if (side == ConflictSide.OURS && chunk.resolution != ConflictResolution.Unresolved) {
                    GutterButton(
                        painter = painterResource(Res.drawable.close),
                        tooltip = stringResource(Res.string.conflict_resolver_reset_conflict),
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        onClick = { actions.onResolve(row.chunk, ConflictResolution.Unresolved) },
                    )
                }
            }
        }
    }
}

/** A gutter's button: [filled] for a conflict's arrows, the main thing to click, as in the mockup. */
@Composable
private fun GutterButton(painter: Painter, tooltip: String, color: Color, filled: Boolean = false, onClick: () -> Unit) {
    DelayedTooltip(tooltip) {
        Icon(
            painter = painter,
            contentDescription = tooltip,
            tint = if (filled) OnArrowColor else color,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(width = 26.dp, height = 20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (filled) color else Color.Transparent)
                .handMouseClickable { onClick() }
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** Every resolution of a conflict, the ones without an arrow included. */
private fun conflictMenu(chunk: Int, sides: ConflictSides?, actions: ColumnActions): List<ContextMenuElement> =
    mutableListOf<ContextMenuElement>().apply {
        fun item(label: @Composable () -> String, resolution: ConflictResolution) {
            addContextMenu(composableLabel = label, onClick = { actions.onResolve(chunk, resolution) })
        }

        @Composable
        fun name(side: ConflictSide) = sides?.label(side) ?: side.name.lowercase()

        item({ stringResource(Res.string.conflict_resolver_menu_ours, name(ConflictSide.OURS)) }, ConflictResolution.Ours)
        item(
            { stringResource(Res.string.conflict_resolver_menu_theirs, name(ConflictSide.THEIRS)) },
            ConflictResolution.Theirs,
        )
        item({ stringResource(Res.string.conflict_resolver_menu_ours_then_theirs) }, ConflictResolution.OursThenTheirs)
        item({ stringResource(Res.string.conflict_resolver_menu_theirs_then_ours) }, ConflictResolution.TheirsThenOurs)
        item({ stringResource(Res.string.conflict_resolver_menu_base) }, ConflictResolution.Base)
        add(ContextMenuElement.ContextSeparator)
        item({ stringResource(Res.string.conflict_resolver_menu_reset) }, ConflictResolution.Unresolved)
    }

/** The bottom line: what the colours and the buttons mean. */
@Composable
fun ResolverLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LegendItem(MaterialTheme.colors.primary, stringResource(Res.string.conflict_resolver_legend_ours))
        LegendItem(TheirsColor, stringResource(Res.string.conflict_resolver_legend_theirs))
        LegendItem(MaterialTheme.colors.conflictFile, stringResource(Res.string.conflict_resolver_legend_conflict))
        LegendItem(MaterialTheme.colors.addFile, stringResource(Res.string.conflict_resolver_legend_resolved))

        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(Res.string.conflict_resolver_legend_keys),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(
            text = text,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(start = 5.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CellKind.background(): Color = when (this) {
    CellKind.UNCHANGED -> Color.Transparent
    CellKind.CHANGED_BY_OURS -> MaterialTheme.colors.primary.copy(alpha = 0.16f)
    CellKind.CHANGED_BY_THEIRS -> TheirsColor.copy(alpha = 0.18f)
    CellKind.CHANGED_BY_BOTH -> MaterialTheme.colors.onBackground.copy(alpha = 0.07f)
    CellKind.CONFLICT, CellKind.OPEN_CONFLICT -> MaterialTheme.colors.conflictFile.copy(alpha = 0.16f)
    CellKind.RESOLVED -> MaterialTheme.colors.addFile.copy(alpha = 0.16f)
}

/** The current conflict's cells get a bar on their left edge. */
@Composable
private fun Modifier.currentConflictMark(isCurrent: Boolean): Modifier {
    if (!isCurrent) {
        return this
    }

    val color = MaterialTheme.colors.conflictFile

    return drawBehind { drawRect(color, size = size.copy(width = 3.dp.toPx())) }
}

/** Diagonal lines, as the fillers and placeholders of Meld and Beyond Compare. */
private fun Modifier.hatched(color: Color): Modifier = drawBehind {
    val step = 8.dp.toPx()
    var x = -size.height

    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
        x += step
    }
}
