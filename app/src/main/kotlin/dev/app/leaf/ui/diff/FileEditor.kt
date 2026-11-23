// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.file_edit_back
import dev.app.leaf.app.generated.resources.file_edit_edited
import dev.app.leaf.app.generated.resources.file_edit_field
import dev.app.leaf.app.generated.resources.file_edit_put_back
import dev.app.leaf.app.generated.resources.file_edit_removed_here
import dev.app.leaf.app.generated.resources.file_edit_save
import dev.app.leaf.app.generated.resources.file_edit_was
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.editing.ChangeKind
import dev.app.leaf.domain.editing.ChangeMarker
import dev.app.leaf.domain.editing.occurrencesAt
import dev.app.leaf.repositoryopen.FileEditState
import dev.app.leaf.repositoryopen.HunkEditLeave
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.deleteFile
import dev.app.leaf.theme.diffLineRemoved
import dev.app.leaf.theme.headerBackground
import dev.app.leaf.theme.inCodeFont
import dev.app.leaf.theme.modifyFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.styleColors
import dev.app.leaf.theme.syntaxColors
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.SidewaysScroll
import dev.app.leaf.ui.components.SidewaysScrollbar
import dev.app.leaf.ui.components.scrollsSideways
import dev.app.leaf.ui.conflicts.EditorColours
import dev.app.leaf.ui.conflicts.EditorLineNumbers
import dev.app.leaf.ui.conflicts.FieldBorder
import dev.app.leaf.ui.conflicts.FieldEndMargin
import dev.app.leaf.ui.conflicts.FieldSidewaysElement
import dev.app.leaf.ui.conflicts.TabsAsSpaces
import dev.app.leaf.ui.editor.FileNameMenu
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.io.File

/** What the whole file's editor does (fork-only, `docs/fork/inline-editing.md`, B2), from the view model. */
@Immutable
class FileEditing(
    val state: FileEditState,
    val onType: (TextFieldValue) -> Unit,
    val onSave: () -> Unit,
    val onSaveAndClose: () -> Unit,
    val onBack: () -> Unit,
    val onShowMarker: (ChangeMarker?) -> Unit,
    val onPutBack: (ChangeMarker) -> Unit,
    val onLeaveSave: () -> Unit,
    val onLeaveDiscard: () -> Unit,
    val onKeepEditing: () -> Unit,
)

/** The marks of the name under the caret, in the code's own background colour for matches. */
private val OccurrenceColor = Color(0x61E9C754)

/** The marker column's width, before the field's text. */
private val MarkersWidth = 14.dp

/**
 * The working file edited whole, in the diff pane's place (fork-only, B2): its header (the name with its menu, "Edited"
 * while something isn't saved, Back to diff and Save), the question about lines not saved when leaving, and the file:
 * line numbers, markers of the changes against the index, the text, and down the right edge the whole file's changes
 * and the name under the caret, with where the view is. [wrapLines] is the diff's own setting.
 */
@Composable
fun FileEditorPane(editing: FileEditing, wrapLines: Boolean) {
    val state = editing.state
    val path = state.edit.path

    Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        FileEditorHeader(editing)

        state.leave?.let { leave ->
            EditLeaveStripContent(
                fileName = File(path).name,
                leave = leave,
                onSave = editing.onLeaveSave,
                onDiscard = editing.onLeaveDiscard,
                onKeepEditing = editing.onKeepEditing,
            )
        }

        FileEditorBody(editing, wrapLines, Modifier.weight(1f))
    }
}

@Composable
private fun FileEditorHeader(editing: FileEditing) {
    val state = editing.state
    val path = state.edit.path
    val folder = path.substringBeforeLast('/', missingDelimiterValue = "")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .headerBackground()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (folder.isNotEmpty()) {
                Text(
                    text = "$folder/",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            FileNameMenu(
                name = path.substringAfterLast('/'),
                path = path,
                canOpen = true,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )

            if (state.hasUnsavedChanges) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colors.onBackgroundSecondary))
                    Text(
                        text = stringResource(Res.string.file_edit_edited),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                text = stringResource(Res.string.file_edit_back),
                colors = MaterialTheme.styleColors.neutral,
                onClick = editing.onBack,
            )
            SecondaryButton(
                text = stringResource(Res.string.file_edit_save),
                colors = MaterialTheme.styleColors.mainAction,
                onClick = editing.onSave,
            )
        }
    }
}

@Composable
private fun FileEditorBody(editing: FileEditing, wrapLines: Boolean, modifier: Modifier) {
    val state = editing.state
    val text = state.value.text
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val coroutines = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val sideways = remember(wrapLines) { if (wrapLines) null else SidewaysScroll() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableStateOf(0) }
    val style = MaterialTheme.typography.body2.inCodeFont().copy(color = MaterialTheme.colors.onBackground)
    val lineStarts = remember(text) { lineStartsOf(text) }
    val occurrences = remember(text, state.value.selection) {
        if (state.value.selection.collapsed) occurrencesAt(text, state.value.selection.start) else emptyList()
    }
    val colours = EditorColours(state.syntax, MaterialTheme.syntaxColors)
    val transformation = remember(colours, occurrences) { OccurrenceMarks(colours, occurrences) }
    val numberWidth = maxOf(2, lineStarts.size.toString().length)
    val fieldName = stringResource(Res.string.file_edit_field, File(state.edit.path).name)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Once laid out, the caret's line shows, a third of the view down: a hunk's lines carried over, or line 1
    var scrolledToCaret by remember { mutableStateOf(false) }
    LaunchedEffect(layout != null) {
        val laidOut = layout ?: return@LaunchedEffect
        if (scrolledToCaret) return@LaunchedEffect
        scrolledToCaret = true

        val caret = TabsAsSpaces.offset(text, state.value.selection.end)
        val top = laidOut.getLineTop(laidOut.getLineForOffset(caret.coerceAtMost(laidOut.layoutInput.text.length)))
        scroll.scrollTo((top - viewportHeight / 3f).toInt().coerceAtLeast(0))
    }

    DisposableEffect(sideways) {
        onDispose { sideways?.editorWidth = 0f }
    }

    Row(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { viewportHeight = it.height }
                .scrollsSideways(sideways),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 4.dp)) {
                    EditorLineNumbers(1, numberWidth, text, layout)

                    ChangeMarkers(
                        markers = state.markers,
                        openMarker = state.openMarker,
                        lineStarts = lineStarts,
                        text = text,
                        layout = layout,
                        onShowMarker = editing.onShowMarker,
                        onPutBack = editing.onPutBack,
                    )

                    BasicTextField(
                        value = state.value,
                        onValueChange = editing.onType,
                        textStyle = style,
                        cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
                        visualTransformation = transformation,
                        onTextLayout = { result ->
                            layout = result
                            sideways?.editorWidth = result.multiParagraph.maxIntrinsicWidth + with(density) { FieldEndMargin.toPx() }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onSizeChanged { sideways?.fit(0f, it.width.toFloat()) }
                            .clipToBounds()
                            .returningFocusOnDispose()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = fieldName }
                            .onPreviewKeyEvent { event ->
                                val command = if (currentOs == OS.MAC) event.isMetaPressed else event.isCtrlPressed

                                if (event.key == Key.Enter && command && event.type == KeyEventType.KeyDown) {
                                    editing.onSaveAndClose()
                                    true
                                } else {
                                    false
                                }
                            }
                            .then(if (sideways != null) FieldSidewaysElement(sideways) else Modifier)
                            .padding(top = FieldBorder, bottom = FieldBorder),
                    )
                }
            }

            SidewaysScrollbar(sideways)
        }

        OverviewRuler(
            markers = state.markers,
            occurrenceLines = remember(occurrences, lineStarts) { occurrences.map { lineOf(lineStarts, it.first) } },
            lineCount = lineStarts.size,
            scrollValue = scroll.value,
            scrollMax = scroll.maxValue,
            viewportHeight = viewportHeight,
            onScrollTo = { fraction ->
                coroutines.launch {
                    val total = scroll.maxValue + viewportHeight
                    scroll.scrollTo((fraction * total - viewportHeight / 2f).toInt().coerceIn(0, scroll.maxValue))
                }
            },
        )
    }
}

/**
 * The markers before the lines: a bar beside lines added (green) or changed (blue), a notch where lines were removed
 * (red). A changed or a removed one shows the index's lines, with Put it back, when clicked.
 */
@Composable
private fun ChangeMarkers(
    markers: List<ChangeMarker>,
    openMarker: ChangeMarker?,
    lineStarts: List<Int>,
    text: String,
    layout: TextLayoutResult?,
    onShowMarker: (ChangeMarker?) -> Unit,
    onPutBack: (ChangeMarker) -> Unit,
) {
    val added = MaterialTheme.colors.addFile
    val changed = MaterialTheme.colors.modifyFile
    val removed = MaterialTheme.colors.deleteFile
    val density = LocalDensity.current

    fun spanOf(marker: ChangeMarker, laidOut: TextLayoutResult): ClosedFloatingPointRange<Float> {
        val top = FieldBorder.value * density.density
        val lines = lineStarts.size

        return if (marker.kind == ChangeKind.REMOVED) {
            val y = if (marker.start < lines) {
                laidOut.getLineTop(laidOut.getLineForOffset(TabsAsSpaces.offset(text, lineStarts[marker.start])))
            } else {
                laidOut.size.height.toFloat()
            }
            (y + top - 4f)..(y + top + 4f)
        } else {
            val first = laidOut.getLineForOffset(TabsAsSpaces.offset(text, lineStarts[marker.start]))
            val lastStart = lineStarts[marker.end - 1]
            val lastEnd = if (marker.end < lines) lineStarts[marker.end] - 1 else text.length
            val last = laidOut.getLineForOffset(TabsAsSpaces.offset(text, maxOf(lastStart, lastEnd)))
            (laidOut.getLineTop(first) + top)..(laidOut.getLineBottom(last) + top)
        }
    }

    val laidOut = layout?.takeIf { it.layoutInput.text.text == text.replace("\t", TabsAsSpaces.SPACES) }
    // As tall as the text: the lines scroll, so filling the height has nothing to fill
    val height = with(density) { ((layout?.size?.height ?: 0) + 2 * FieldBorder.roundToPx()).toDp() }

    Box(
        modifier = Modifier
            .width(MarkersWidth)
            .height(height)
            .pointerInput(markers, laidOut) {
                detectTapGestures { offset ->
                    val at = laidOut ?: return@detectTapGestures
                    val marker = markers.firstOrNull { marker ->
                        marker.kind != ChangeKind.ADDED && offset.y in spanOf(marker, at)
                    }
                    onShowMarker(marker)
                }
            }
            .drawBehind {
                val at = laidOut ?: return@drawBehind
                val barWidth = 3.dp.toPx()
                val left = size.width - barWidth - 4.dp.toPx()

                for (marker in markers) {
                    val span = spanOf(marker, at)

                    if (marker.kind == ChangeKind.REMOVED) {
                        val y = (span.start + span.endInclusive) / 2
                        val path = Path().apply {
                            moveTo(left - 2.dp.toPx(), y - 4.dp.toPx())
                            lineTo(left + barWidth + 2.dp.toPx(), y)
                            lineTo(left - 2.dp.toPx(), y + 4.dp.toPx())
                            close()
                        }
                        drawPath(path, removed)
                    } else {
                        val color = if (marker.kind == ChangeKind.ADDED) added else changed
                        drawRect(color, Offset(left, span.start), Size(barWidth, span.endInclusive - span.start))
                    }
                }
            },
    ) {
        val open = openMarker
        if (open != null && laidOut != null) {
            val y = spanOf(open, laidOut).endInclusive.toInt()

            Popup(
                offset = IntOffset(with(density) { MarkersWidth.roundToPx() }, y),
                onDismissRequest = { onShowMarker(null) },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                OldLinesPopover(open, onPutBack = { onPutBack(open) })
            }
        }
    }
}

/** The index's lines of a changed or removed marker, with Put it back. */
@Composable
private fun OldLinesPopover(marker: ChangeMarker, onPutBack: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 640.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.background)
            .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
    ) {
        Text(
            text = if (marker.kind == ChangeKind.REMOVED) {
                stringResource(Res.string.file_edit_removed_here)
            } else {
                stringResource(Res.string.file_edit_was)
            },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 10.dp),
        )

        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MaterialTheme.colors.diffLineRemoved)) {
            for (line in marker.oldLines.take(MAX_POPOVER_LINES)) {
                Text(
                    text = line.replace("\t", TabsAsSpaces.SPACES),
                    style = MaterialTheme.typography.body2.inCodeFont(),
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }

            if (marker.oldLines.size > MAX_POPOVER_LINES) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.body2.inCodeFont(),
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            SecondaryButton(
                text = stringResource(Res.string.file_edit_put_back),
                colors = MaterialTheme.styleColors.neutral,
                onClick = onPutBack,
            )
        }
    }
}

/** The most of a change's old lines that its popover shows. */
private const val MAX_POPOVER_LINES = 20

/**
 * Down the right edge: the whole file's changes (the markers' colours) and the places of the name under the caret, by
 * their line's place in the file, and where the view is. A click scrolls there.
 */
@Composable
private fun OverviewRuler(
    markers: List<ChangeMarker>,
    occurrenceLines: List<Int>,
    lineCount: Int,
    scrollValue: Int,
    scrollMax: Int,
    viewportHeight: Int,
    onScrollTo: (Float) -> Unit,
) {
    val added = MaterialTheme.colors.addFile
    val changed = MaterialTheme.colors.modifyFile
    val removed = MaterialTheme.colors.deleteFile
    val thumb = MaterialTheme.colors.onBackground.copy(alpha = 0.12f)
    val edge = MaterialTheme.colors.onBackground.copy(alpha = 0.1f)

    Canvas(
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colors.surface)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onScrollTo(offset.y / size.height) }
            },
    ) {
        drawRect(edge, Offset.Zero, Size(1f, size.height))

        val total = scrollMax + viewportHeight
        if (total > 0 && scrollMax > 0) {
            val top = scrollValue / total.toFloat() * size.height
            val height = viewportHeight / total.toFloat() * size.height
            drawRect(thumb, Offset(2f, top), Size(size.width - 4f, height))
        }

        val lines = maxOf(1, lineCount)
        fun yOf(line: Int) = line / lines.toFloat() * size.height
        val tick = 3.dp.toPx()

        for (line in occurrenceLines) {
            drawRect(OccurrenceColor.copy(alpha = 1f), Offset(size.width / 2, yOf(line)), Size(size.width / 2 - 2f, tick))
        }

        for (marker in markers) {
            val color = when (marker.kind) {
                ChangeKind.ADDED -> added
                ChangeKind.CHANGED -> changed
                ChangeKind.REMOVED -> removed
            }
            val height = maxOf(tick, yOf(marker.end) - yOf(marker.start))
            drawRect(color, Offset(2f, yOf(marker.start)), Size(size.width / 2 - 2f, height))
        }
    }
}

/**
 * The places of the name under the caret, as a background over [base]'s text: the code's colours, and tabs as four
 * spaces, which the places are mapped through.
 */
internal class OccurrenceMarks(private val base: VisualTransformation, private val ranges: List<IntRange>) :
    VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = base.filter(text)

        if (ranges.isEmpty()) return transformed

        val builder = AnnotatedString.Builder(transformed.text)
        val shown = transformed.text.length

        for (range in ranges) {
            val start = transformed.offsetMapping.originalToTransformed(range.first).coerceIn(0, shown)
            val end = transformed.offsetMapping.originalToTransformed(range.last + 1).coerceIn(0, shown)
            if (start < end) builder.addStyle(SpanStyle(background = OccurrenceColor), start, end)
        }

        return TransformedText(builder.toAnnotatedString(), transformed.offsetMapping)
    }

    override fun equals(other: Any?) = other is OccurrenceMarks && other.base == base && other.ranges == ranges

    override fun hashCode() = 31 * base.hashCode() + ranges.hashCode()
}

/** Where each line of [text] starts. */
internal fun lineStartsOf(text: String): List<Int> = buildList {
    add(0)
    text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
}

/** The line (from 0) that [offset] of the text is on, by [lineStarts]. */
internal fun lineOf(lineStarts: List<Int>, offset: Int): Int {
    val found = lineStarts.binarySearch(offset)
    return if (found >= 0) found else -found - 2
}
