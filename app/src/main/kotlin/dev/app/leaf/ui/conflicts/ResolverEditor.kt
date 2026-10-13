// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.onDoubleClick
import dev.app.leaf.extensions.toStringWithSpaces
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.diff.LineNumber
import dev.app.leaf.viewmodels.ResolverEdit
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** What the text field of a part being edited does: it changed, or editing is done. */
class EditActions(
    val onType: (TextFieldValue) -> Unit,
    val onFinish: () -> Unit,
)

/** The part of the result being edited, numbered from [firstNumber] as the result is, and what its text field does. */
class PartEditing(val edit: ResolverEdit, val firstNumber: Int, val actions: EditActions)

/** The space between the line numbers and the field's border, the border, and between the border and the text. */
private val FieldStart = 10.dp
private val FieldBorder = 1.dp
private val FieldTextStart = 5.dp

/** The space left after the longest line typed, as after the file's longest line, while lines don't wrap. */
private val FieldEndMargin = 24.dp

/** A key for a row of the lists: its chunk and its row in the chunk, which stay when other chunks change their rows. */
internal fun rowKey(chunk: Int, row: Int): Long = (chunk.toLong() shl 32) or row.toLong()

private fun chunkOfKey(key: Any?): Int? = (key as? Long)?.let { (it shr 32).toInt() }

private fun rowOfKey(key: Long): Int = (key and 0xFFFFFFFFL).toInt()

/** An item of a list: a row, or the text field in place of the rows of the part being edited. */
internal sealed interface ListItem<R> {
    val chunk: Int
    val key: Long

    class Line<R>(val row: R, override val chunk: Int, val rowInChunk: Int) : ListItem<R> {
        override val key = rowKey(chunk, rowInChunk)
    }

    /** The part's [rows], which the field replaces, keyed as its row [anchorRow] (see [EditorPlace]). */
    class Editor<R>(val rows: List<R>, override val chunk: Int, anchorRow: Int) : ListItem<R> {
        override val key = rowKey(chunk, anchorRow)
    }
}

/**
 * [rows], as a list shows them: one item per row, except the rows of [editChunk], which make one item, the editor,
 * keyed as their row [anchorRow].
 */
internal fun <R> listItems(rows: List<R>, chunkOf: (R) -> Int, editChunk: Int?, anchorRow: Int): List<ListItem<R>> {
    val items = mutableListOf<ListItem<R>>()
    var chunk = -1
    var rowInChunk = 0
    var editorRows: MutableList<R>? = null

    for (row in rows) {
        val rowChunk = chunkOf(row)

        rowInChunk = if (rowChunk == chunk) rowInChunk + 1 else 0
        chunk = rowChunk

        when {
            rowChunk != editChunk -> items += ListItem.Line(row, rowChunk, rowInChunk)
            editorRows == null -> {
                editorRows = mutableListOf(row)
                items += ListItem.Editor(editorRows, rowChunk, anchorRow)
            }

            else -> editorRows += row
        }
    }

    return items
}

/**
 * Keeps a list still while a part's rows turn into its text field and back. LazyColumn keeps its first item where it
 * was, by key, which is all it takes while the part starts on screen. When the list's top is inside the part:
 * - the field is keyed as the row at the top ([anchorRow]), so that LazyColumn keeps it where that row was, and once
 *   it's laid out it moves so that the row's line is there ([moveToAnchor]);
 * - when editing ends, the line at the top goes back to the top as a row ([end], [Restore]).
 */
@Stable
class EditorPlace(private val list: LazyListState) {
    /** The part edited, and its row that keeps its place. */
    private var chunk: Int? = null
    internal var anchorRow = 0
        private set

    /** Where the anchor row was, from the top of the list, in pixels, when the list's top was inside the part. */
    private var anchorTop: Int? = null

    /** The field's text as it was last laid out, with its tabs as spaces, and its top in the item, in pixels. */
    private var layout: TextLayoutResult? = null
    private var textTop = 0f
    private var moved = false

    /** The row and how far into it the list goes back to once editing ended, until [Restore] scrolls to it. */
    private var exit: Pair<Long, Int>? = null

    /** While composing the list, before it shows the field in place of [chunk]'s rows. */
    internal fun start(chunk: Int) {
        if (this.chunk == chunk) {
            return
        }

        // Not a read that recomposes the list on each scroll
        val top = Snapshot.withoutReadObservation { list.layoutInfo.visibleItemsInfo.firstOrNull() }

        this.chunk = chunk
        layout = null
        moved = false
        exit = null

        if (top != null && chunkOfKey(top.key) == chunk) {
            anchorRow = rowOfKey(top.key as Long)
            anchorTop = top.offset
        } else {
            anchorRow = 0
            anchorTop = null
        }
    }

    /** The field was laid out. */
    internal fun laidOut(result: TextLayoutResult, textTop: Float) {
        layout = result
        this.textTop = textTop
    }

    /** Once the field is laid out the first time: moves the list for the anchor row's line to be where the row was. */
    internal suspend fun moveToAnchor() {
        if (moved) {
            return
        }

        moved = true

        val anchorTop = anchorTop ?: return
        val layout = layout ?: return
        val key = rowKey(chunk ?: return, anchorRow)
        val editor = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val lineTop = textTop + layout.getLineTop(layout.getLineForOffset(lineStart(layout, anchorRow)))
        // The field's top goes where the line's top is anchorTop
        val distance = editor.offset - (anchorTop - lineTop.roundToInt())

        if (distance != 0) {
            list.scrollBy(distance.toFloat())
        }
    }

    /** While composing the list, once the field is gone: notes the line at the list's top, if the field was there. */
    internal fun end() {
        val chunk = chunk ?: return
        val layout = layout

        this.chunk = null

        val top = Snapshot.withoutReadObservation { list.layoutInfo.visibleItemsInfo.firstOrNull() }

        if (top == null || top.key != rowKey(chunk, anchorRow) || top.offset >= 0 || layout == null) {
            return
        }

        val y = (-top.offset - textTop).coerceAtLeast(0f)
        val text = layout.layoutInput.text.text
        val visualLine = layout.getLineForVerticalPosition(y)
        val line = text.take(layout.getLineStart(visualLine)).count { it == '\n' }
        val lineTop = layout.getLineTop(layout.getLineForOffset(lineStart(layout, line)))

        exit = rowKey(chunk, line) to (y - lineTop).roundToInt().coerceAtLeast(0)
    }

    /** While composing the list's rows: once editing ended, goes back to the row noted at the list's top, if any. */
    @Composable
    internal fun <R> Restore(items: List<ListItem<R>>) {
        val (key, offset) = exit ?: return
        val index = items.indexOfFirst { it.key == key }

        SideEffect {
            exit = null

            if (index >= 0) {
                list.requestScrollToItem(index, offset)
            }
        }
    }

    /** Where line [line] of the field starts, in its laid out text, or its last line's start. */
    private fun lineStart(layout: TextLayoutResult, line: Int): Int {
        val text = layout.layoutInput.text.text
        var start = 0

        repeat(line) {
            val next = text.indexOf('\n', start)

            if (next < 0) {
                return start
            }

            start = next + 1
        }

        return start
    }
}

/** Presses inside the field, so that a press elsewhere in the resolver finishes editing. */
@Stable
internal class EditorPresses {
    var inside = false
}

internal val LocalEditorPresses = compositionLocalOf { EditorPresses() }

/**
 * While a part is edited ([editing]), a press anywhere in the resolver but in its field finishes editing ([onFinish]),
 * and the press goes on to what it was for. The field notes its own presses first ([EditorPresses]).
 */
internal fun Modifier.finishingEditOnPressOutside(editing: Boolean, presses: EditorPresses, onFinish: () -> Unit) =
    if (!editing) {
        this
    } else {
        pointerInput(presses) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)

                    if (event.type == PointerEventType.Press) {
                        if (!presses.inside) onFinish()
                        presses.inside = false
                    }
                }
            }
        }
    }

/**
 * The text field of a part of the result, with the result's line numbers next to its lines, from
 * [PartEditing.firstNumber]. It takes the focus when it shows, and finishes editing when it leaves the composition, as
 * when the layout changes. Scrolled out of sight, it stays, as LazyColumn keeps the focused item, and typing brings it
 * back. Tabs show as four spaces, as the rows show them. While lines don't wrap, it scrolls sideways with the columns,
 * and keeps the caret in sight.
 */
@Composable
internal fun PartEditor(
    editing: PartEditing,
    numberWidth: Int,
    place: EditorPlace,
    modifier: Modifier = Modifier,
) {
    val edit = editing.edit
    val presses = LocalEditorPresses.current
    val sideways = LocalSidewaysScroll.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val onFinish by rememberUpdatedState(editing.actions.onFinish)
    val style = MaterialTheme.typography.body2.copy(
        fontFamily = monoTypography(),
        color = MaterialTheme.colors.onBackground,
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(layout != null) {
        if (layout != null) {
            place.moveToAnchor()
        }
    }

    // Gone with the layout, or the file: what was typed is kept
    DisposableEffect(Unit) {
        onDispose {
            sideways?.editorWidth = 0f
            onFinish()
        }
    }

    // While lines don't wrap, the caret stays in sight
    if (sideways != null) {
        val caret = edit.value.selection.end

        LaunchedEffect(caret, layout) {
            val laidOut = layout ?: return@LaunchedEffect
            val transformed = TabsAsSpaces.offset(edit.value.text, caret)
            val x = laidOut.getCursorRect(transformed.coerceAtMost(laidOut.layoutInput.text.length)).left
            val margin = with(density) { FieldEndMargin.toPx() }

            when {
                x < sideways.offset -> sideways.scrollTo(x - margin)
                x > sideways.offset + sideways.viewport - margin -> sideways.scrollTo(x - sideways.viewport + margin)
            }
        }
    }

    Row(modifier.fillMaxWidth()) {
        EditorLineNumbers(editing.firstNumber, numberWidth, edit.value.text, layout)

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = FieldStart, end = 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colors.secondarySurface)
                .border(FieldBorder, MaterialTheme.colors.primaryVariant, RoundedCornerShape(3.dp))
                .pointerInput(presses) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                                presses.inside = true
                            }
                        }
                    }
                }
                .clipToBounds(),
        ) {
            BasicTextField(
                value = edit.value,
                onValueChange = editing.actions.onType,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
                visualTransformation = TabsAsSpaces,
                onTextLayout = { result ->
                    layout = result
                    place.laidOut(result, textTop = with(density) { FieldBorder.toPx() })

                    if (sideways != null) {
                        sideways.editorWidth =
                            result.multiParagraph.maxIntrinsicWidth + with(density) { FieldEndMargin.toPx() }
                    }
                },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .then(if (sideways != null) FieldSidewaysElement(sideways) else Modifier.fillMaxWidth())
                    .padding(start = FieldTextStart, top = FieldBorder, bottom = FieldBorder),
            )
        }
    }
}

/** The result's line numbers next to the field's lines, from [first], placed where the field lays each line out. */
@Composable
private fun EditorLineNumbers(first: Int, numberWidth: Int, text: String, layout: TextLayoutResult?) {
    val starts = remember(text) {
        buildList {
            add(0)
            text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
    }

    Layout(
        content = {
            for (line in starts.indices) {
                LineNumber(text = (first + line).toStringWithSpaces(numberWidth), remarked = true)
            }
        },
    ) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val laidOut = layout?.takeIf { it.layoutInput.text.text == text.replace("\t", TabsAsSpaces.SPACES) }

        layout(width, placeables.firstOrNull()?.height ?: 0) {
            // Until the field is laid out with this text, which is right after typing
            if (laidOut != null) {
                placeables.forEachIndexed { line, placeable ->
                    val offset = TabsAsSpaces.offset(text, starts[line])
                    val top = laidOut.getLineTop(laidOut.getLineForOffset(offset)) + FieldBorder.toPx()

                    placeable.place(0, top.roundToInt())
                }
            }
        }
    }
}

/**
 * Tabs as four spaces, as [dev.app.leaf.ui.diff.DiffText] shows them, so that lines don't move when editing starts. The
 * text keeps its tabs.
 */
internal object TabsAsSpaces : VisualTransformation {
    const val SPACES = "    "

    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text

        if ('\t' !in original) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        return TransformedText(AnnotatedString(original.replace("\t", SPACES)), Mapping(original))
    }

    /** Where [offset] of [original] is in the text that the field lays out. */
    fun offset(original: String, offset: Int): Int {
        val end = offset.coerceIn(0, original.length)

        return end + (0 until end).count { original[it] == '\t' } * (SPACES.length - 1)
    }

    private class Mapping(private val original: String) : OffsetMapping {
        private val toOriginal = buildList {
            original.forEachIndexed { index, char -> repeat(if (char == '\t') SPACES.length else 1) { add(index) } }
            add(original.length)
        }

        override fun originalToTransformed(offset: Int) = offset(original, offset)

        override fun transformedToOriginal(offset: Int) = toOriginal[offset.coerceIn(0, toOriginal.lastIndex)]
    }
}

/**
 * Lays the field out on lines that don't wrap, at least as wide as the column, and places it the sideways offset to
 * the left, as the rows' lines are.
 */
private class FieldSidewaysElement(private val sideways: SidewaysScroll) : ModifierNodeElement<FieldSidewaysNode>() {
    override fun create() = FieldSidewaysNode(sideways)

    override fun update(node: FieldSidewaysNode) {
        node.sideways = sideways
    }

    override fun equals(other: Any?) = other is FieldSidewaysElement && other.sideways === sideways

    override fun hashCode() = System.identityHashCode(sideways)
}

private class FieldSidewaysNode(var sideways: SidewaysScroll) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val offset = sideways.offset.roundToInt()

        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)

            return layout(placeable.width, placeable.height) { placeable.placeRelative(-offset, 0) }
        }

        val width = constraints.maxWidth
        // Wide enough to take clicks right of short lines, wherever the lines are scrolled to
        val placeable = measurable.measure(constraints.copy(minWidth = width + offset, maxWidth = Constraints.Infinity))

        return layout(width, placeable.height) {
            placeable.placeRelative(-offset, 0)
        }
    }
}

/**
 * A row of the result that can be edited ([canEdit]): a double-click edits its part, with the caret on its [line], and
 * the pointer over it shows Edit. The first row of a part that was [edited] has a mark instead.
 */
@Composable
internal fun EditableRow(
    chunk: Int,
    line: Int,
    first: Boolean,
    edited: Boolean,
    canEdit: Boolean,
    onEdit: (chunk: Int, line: Int?) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .hoverable(interactions, enabled = canEdit)
            .then(if (canEdit) Modifier.onDoubleClick { onEdit(chunk, line) } else Modifier),
    ) {
        content()

        when {
            hovered && canEdit -> EditButton(onClick = { onEdit(chunk, line) }, Modifier.align(Alignment.TopEnd))
            edited && first -> EditedMark(Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun EditButton(onClick: () -> Unit, modifier: Modifier) {
    DelayedTooltip(stringResource(Res.string.conflict_resolver_edit_tooltip), modifier = modifier.padding(end = 6.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.secondarySurface)
                .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                .handMouseClickable { onClick() }
                .padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.edit),
                contentDescription = null,
                tint = MaterialTheme.colors.onBackground,
                modifier = Modifier.padding(end = 3.dp).size(12.dp),
            )
            Text(
                text = stringResource(Res.string.conflict_resolver_edit),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EditedMark(modifier: Modifier) {
    val description = stringResource(Res.string.conflict_resolver_edited_tooltip)

    DelayedTooltip(description, modifier = modifier.padding(end = 8.dp)) {
        Icon(
            painter = painterResource(Res.drawable.edit),
            contentDescription = description,
            tint = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(top = 3.dp).size(12.dp),
        )
    }
}
