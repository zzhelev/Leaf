// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.hunk_edit_cancel
import dev.app.leaf.app.generated.resources.hunk_edit_discard
import dev.app.leaf.app.generated.resources.hunk_edit_done
import dev.app.leaf.app.generated.resources.hunk_edit_editing
import dev.app.leaf.app.generated.resources.hunk_edit_field
import dev.app.leaf.app.generated.resources.hunk_edit_keep_editing
import dev.app.leaf.app.generated.resources.hunk_edit_left
import dev.app.leaf.app.generated.resources.hunk_edit_save
import dev.app.leaf.app.generated.resources.hunk_edit_unsaved
import dev.app.leaf.app.generated.resources.warning
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.repositoryopen.HunkEditState
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.inCodeFont
import dev.app.leaf.theme.styleColors
import dev.app.leaf.theme.syntaxColors
import dev.app.leaf.ui.components.LocalSidewaysScroll
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.conflicts.EditorColours
import dev.app.leaf.ui.conflicts.EditorLineNumbers
import dev.app.leaf.ui.conflicts.FieldBorder
import dev.app.leaf.ui.conflicts.FieldEndMargin
import dev.app.leaf.ui.conflicts.FieldSidewaysElement
import dev.app.leaf.ui.conflicts.FieldTextStart
import dev.app.leaf.ui.conflicts.TabsAsSpaces
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * A hunk of the diff edited in place (fork-only, `docs/fork/inline-editing.md`): the edit, if one is under way, which
 * hunks can be edited, and what the field and the hunk's header do. The diff pane provides it as [LocalHunkEditing], so
 * that upstream's hunk lists, which file history shares, keep their parameters.
 */
@Immutable
class HunkEditing(
    val state: HunkEditState?,
    val canEdit: (Hunk) -> Boolean,
    val onStart: (hunk: Hunk, caretLine: Int?) -> Unit,
    val onType: (TextFieldValue) -> Unit,
    val onDone: () -> Unit,
    val onCancel: () -> Unit,
) {
    /** The hunk being edited, which no other hunk's buttons may change the file under. */
    val editedHunk: Hunk? get() = state?.hunk

    /** Whether [hunk] offers Edit: no other edit is under way. */
    fun offersEdit(hunk: Hunk): Boolean = state == null && canEdit(hunk)
}

val LocalHunkEditing = compositionLocalOf<HunkEditing?> { null }

/** The hunk header's part while its hunk is edited: "Editing", Cancel and Done, in place of the hunk's buttons. */
@Composable
fun HunkEditControls(editing: HunkEditing) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        val pill = MaterialTheme.styleColors.action

        Text(
            text = stringResource(Res.string.hunk_edit_editing),
            style = MaterialTheme.typography.caption,
            color = pill.content,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(pill.background)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        )

        SecondaryButton(
            text = stringResource(Res.string.hunk_edit_cancel),
            colors = MaterialTheme.styleColors.neutral,
            onClick = editing.onCancel,
        )

        SecondaryButton(
            text = stringResource(Res.string.hunk_edit_done),
            colors = MaterialTheme.styleColors.mainAction,
            onClick = editing.onDone,
        )
    }
}

/**
 * The field in place of an edited hunk's new side, in a unified diff's place: the new side's numbers where its lines
 * are, from [firstNumber], a green bar on each line that differs from the old side, and the lines, in the code's
 * colours, which tabs show as four spaces. [textStart] is where the diff's lines start their text, which the field's
 * text starts at too.
 */
@Composable
fun HunkEditorField(
    editing: HunkEditing,
    state: HunkEditState,
    numberWidth: Int,
    textStart: Dp,
) {
    val sideways = LocalSidewaysScroll.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val style = MaterialTheme.typography.body2.inCodeFont().copy(color = MaterialTheme.colors.onBackground)
    val colours = EditorColours(state.syntax, MaterialTheme.syntaxColors)
    val text = state.value.text
    val barColor = MaterialTheme.colors.addFile
    val fieldName = stringResource(Res.string.hunk_edit_field, File(state.edit.path).name)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    DisposableEffect(sideways) {
        onDispose { sideways?.editorWidth = 0f }
    }

    // While lines don't wrap, the caret stays in sight
    if (sideways != null) {
        val caret = state.value.selection.end

        LaunchedEffect(caret, layout) {
            val laidOut = layout ?: return@LaunchedEffect
            val transformed = TabsAsSpaces.offset(text, caret)
            val x = laidOut.getCursorRect(transformed.coerceAtMost(laidOut.layoutInput.text.length)).left
            val margin = with(density) { FieldEndMargin.toPx() }

            when {
                x < sideways.offset -> sideways.scrollTo(x - margin)
                x > sideways.offset + sideways.viewport - margin -> sideways.scrollTo(x - sideways.viewport + margin)
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        // The old side's numbers, which the new lines don't have
        LineNumber(text = emptyLineNumber(numberWidth), remarked = false)
        EditorLineNumbers(state.edit.start + 1, numberWidth, text, layout)

        // The bars: the lines that differ from the old side
        Box(
            modifier = Modifier
                .width(maxOf(0.dp, textStart - FieldTextStart - FieldBorder))
                .drawBehind {
                    val laidOut = layout?.takeIf { it.layoutInput.text.text == text.replace("\t", TabsAsSpaces.SPACES) }
                        ?: return@drawBehind
                    val lines = text.split('\n')
                    var start = 0
                    val barWidth = 3.dp.toPx()
                    val left = size.width - barWidth - 6.dp.toPx()
                    val top = FieldBorder.toPx()

                    lines.forEachIndexed { index, line ->
                        if (index in state.changedLines) {
                            val first = laidOut.getLineForOffset(TabsAsSpaces.offset(text, start))
                            val last = laidOut.getLineForOffset(TabsAsSpaces.offset(text, start + line.length))
                            val y = laidOut.getLineTop(first) + top

                            drawRect(barColor, Offset(left, y), Size(barWidth, laidOut.getLineBottom(last) + top - y))
                        }

                        start += line.length + 1
                    }
                },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colors.background)
                .border(FieldBorder, MaterialTheme.colors.primaryVariant, RoundedCornerShape(3.dp))
                .clipToBounds(),
        ) {
            BasicTextField(
                value = state.value,
                onValueChange = editing.onType,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
                visualTransformation = colours,
                onTextLayout = { result ->
                    layout = result

                    if (sideways != null) {
                        sideways.editorWidth =
                            result.multiParagraph.maxIntrinsicWidth + with(density) { FieldEndMargin.toPx() }
                    }
                },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = fieldName }
                    .onPreviewKeyEvent { event ->
                        val command = if (currentOs == OS.MAC) event.isMetaPressed else event.isCtrlPressed

                        if (event.key == Key.Enter && command && event.type == KeyEventType.KeyDown) {
                            editing.onDone()
                            true
                        } else {
                            false
                        }
                    }
                    .then(if (sideways != null) FieldSidewaysElement(sideways) else Modifier.fillMaxWidth())
                    .padding(start = FieldTextStart, top = FieldBorder, bottom = FieldBorder),
            )
        }
    }
}

/**
 * Over the diff while an edit with lines typed is left (fork-only, `docs/fork/inline-editing.md`): the selection moved
 * on, Esc, closing the tab or another worktree. Save writes the lines, Discard leaves the file as it was, and Keep
 * editing goes back to the field.
 */
@Composable
fun HunkEditLeaveStrip(
    state: HunkEditState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val strip = MaterialTheme.styleColors.warningStrip
    val name = File(state.edit.path).name
    val leave = state.leave ?: return
    val text = if (leave.then == null && !leave.isEscape) {
        stringResource(Res.string.hunk_edit_left, name)
    } else {
        stringResource(Res.string.hunk_edit_unsaved, name)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(strip.background)
            .bottomEdge(strip.border)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.warning),
            contentDescription = null,
            tint = strip.icon,
            modifier = Modifier.size(16.dp),
        )

        Text(
            text = text,
            color = strip.content,
            style = MaterialTheme.typography.body2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp),
        )

        SecondaryButton(text = stringResource(Res.string.hunk_edit_save), colors = strip.button, onClick = onSave)
        SecondaryButton(
            text = stringResource(Res.string.hunk_edit_discard),
            colors = MaterialTheme.styleColors.destructive,
            onClick = onDiscard,
        )
        SecondaryButton(
            text = stringResource(Res.string.hunk_edit_keep_editing),
            colors = MaterialTheme.styleColors.neutral,
            onClick = onKeepEditing,
        )
    }
}

/** "Edit these lines" of a hunk's line, from that line, while the hunk can be edited; a removed line starts there too. */
fun editLinesAction(editing: HunkEditing?, hunk: Hunk, line: Line): (() -> Unit)? =
    editing?.takeIf { it.offersEdit(hunk) }?.let { { it.onStart(hunk, line.displayNewLineNumber) } }

/**
 * An edited hunk's rows, after its header: its removed lines, dimmed, as a reminder of what was there, then the field in
 * place of its new side.
 */
fun LazyListScope.editedHunkItems(hunk: Hunk, diffType: DiffType, numberWidth: Int, editing: HunkEditing) {
    items(hunk.lines.filter { it.lineType == LineType.REMOVED }) { line ->
        DisableSelection {
            Box(Modifier.alpha(REMOVED_LINES_ALPHA)) {
                DiffLine(numberWidth, line, diffType = diffType, actions = emptyList(), onLineAction = {})
            }
        }
    }

    item {
        val state = editing.state ?: return@item
        val lastNumber = state.edit.start + state.value.text.count { it == '\n' } + 1

        DisableSelection {
            HunkEditorField(
                editing = editing,
                state = state,
                numberWidth = maxOf(numberWidth, lastNumber.toString().length),
                textStart = lineTextStart(diffType),
            )
        }
    }
}

/** How much of an edited hunk's removed lines shows: they're there as a reminder, not to read as the file. */
private const val REMOVED_LINES_ALPHA = 0.6f
