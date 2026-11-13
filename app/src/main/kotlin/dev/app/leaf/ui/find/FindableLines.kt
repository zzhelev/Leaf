// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.find

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import dev.app.leaf.domain.extensions.lineAt
import dev.app.leaf.domain.extensions.removeLineDelimiters
import dev.app.leaf.domain.find.FindMatch
import dev.app.leaf.domain.find.blameFindRows
import dev.app.leaf.domain.find.FindResult
import dev.app.leaf.domain.find.FindRow
import dev.app.leaf.domain.find.LineSide
import dev.app.leaf.domain.find.LineTarget
import dev.app.leaf.domain.find.findMatches
import dev.app.leaf.domain.find.lineSideOf
import dev.app.leaf.domain.find.splitFindRows
import dev.app.leaf.domain.find.targetOfLine
import dev.app.leaf.domain.find.unifiedFindRows
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Line
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.ui.components.LocalSidewaysScroll
import dev.app.leaf.ui.diff.MAX_DIFF_LINE_CHARACTERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.RawText

/**
 * A text that find searches and go to line counts (fork-only, upstream issues 275 and 312): the [rows] of its list as
 * it lays them out, the [lineCount] of the version whose numbers go to line takes, and the text that Select all copies.
 */
interface FindableText {
    val rows: List<FindRow>
    val lineCount: Int
    val isSplit: Boolean

    /** Every line of [cell], as Select all and Copy give them: the lines' own text, without their line endings. */
    fun copyText(cell: Int): String
}

/**
 * A diff's rows: a header row per hunk, then a row per line, or a pair of sides; [rowLines] are the lines they draw.
 */
class DiffFindText(diffResult: DiffResult.TextDiff) : FindableText {
    override val isSplit = diffResult is DiffResult.TextSplit

    override val rows: List<FindRow> = when (diffResult) {
        is DiffResult.Text -> unifiedFindRows(diffResult.hunks, MAX_DIFF_LINE_CHARACTERS)
        is DiffResult.TextSplit -> splitFindRows(diffResult.hunks, MAX_DIFF_LINE_CHARACTERS)
    }

    val rowLines: List<List<Line?>> = when (diffResult) {
        is DiffResult.Text -> diffResult.hunks.flatMap { hunk -> listOf(listOf(null)) + hunk.lines.map { listOf(it) } }
        is DiffResult.TextSplit -> diffResult.hunks.flatMap { splitHunk ->
            listOf(listOf(null, null)) + splitHunk.lines.map { (old, new) -> listOf(old, new) }
        }
    }

    // The hunks carry both versions whole, so their lengths are known in the hunks view too
    override val lineCount: Int = run {
        val hunk = when (diffResult) {
            is DiffResult.Text -> diffResult.hunks.firstOrNull()
            is DiffResult.TextSplit -> diffResult.hunks.firstOrNull()?.sourceHunk
        }
        val text = if (lineSideOf(rows) == LineSide.NEW) hunk?.newText else hunk?.oldText

        text?.size() ?: 0
    }

    override fun copyText(cell: Int): String =
        rowLines.mapNotNull { it.getOrNull(cell) }.joinToString("\n") { it.text.removeLineDelimiters() }
}

/**
 * Goes to [line] of [text]: to its row, or, when the hunks leave it out, through [onShowCompleteFile] to the whole
 * file, after which [goToPendingLine] goes there. Without [onShowCompleteFile], the line isn't gone to.
 */
fun FindState.goToLine(line: Int, text: FindableText, onShowCompleteFile: (() -> Unit)?) {
    when (val target = targetOfLine(text.rows, line, text.lineCount)) {
        is LineTarget.Row -> goTo(target.row)
        LineTarget.NotShown -> if (onShowCompleteFile != null) {
            pendingLine = line
            onShowCompleteFile()
        }

        LineTarget.None -> {}
    }
}

/** Goes to the line that [goToLine] waited with, now that [text] has the whole file. */
fun FindState.goToPendingLine(text: FindableText) {
    val line = pendingLine ?: return

    pendingLine = null
    goToLine(line, text, onShowCompleteFile = null)
}

/**
 * Runs find on [text] while the strip is open, off the UI thread, and scrolls [listState] to the matches and lines that
 * find goes to. [source] is the file shown, which tells another file from a changed one.
 */
@Composable
fun RunFind(state: FindState, text: FindableText, listState: LazyListState, source: Any?) {
    val query = state.query.text
    val matchCase = state.matchCase

    LaunchedEffect(state, text, state.isOpen, state.mode, query, matchCase) {
        if (!state.isOpen || state.mode != FindField.FIND) return@LaunchedEffect

        val found = if (query.isEmpty()) {
            FindResult.NONE
        } else {
            withContext(Dispatchers.Default) { findMatches(text.rows, query, matchCase) }
        }

        state.apply(found, source, listState.firstVisibleItemIndex)
    }

    LaunchedEffect(state, text) {
        state.goToPendingLine(text)
    }

    // The list may be another one by then, as a file history's commit gets one of its own
    val currentListState by rememberUpdatedState(listState)
    val sideways = LocalSidewaysScroll.current

    LaunchedEffect(state.revealRequest) {
        val request = state.revealRequest ?: return@LaunchedEffect

        if (state.takeReveal(request)) {
            currentListState.revealRow(request.row)

            // A line is gone to from its start, as an editor puts the caret there; a match scrolls into sight itself
            if (!request.isMatch) sideways?.scrollTo(0f)
        }
    }
}

/**
 * File history's find (fork-only): its strip's [state], what shows the whole file for go to line, and where the focus
 * goes when the strip closes.
 */
class HistoryFind(val state: FindState, val onShowCompleteFile: () -> Unit, val focusAfterClose: FocusRequester?)

/** What [FindableDiff] needs: the strip's [state], the diff's [text], its list, and the file shown. */
class DiffFind(val state: FindState, val text: DiffFindText, val listState: LazyListState, val source: Any?)

/**
 * A diff's lines that find searches: runs find, marks what it found for the lines below, and selects them all on Select
 * all. [find]'s text is the diff's, which the find strip goes to lines of too. Without [find], just [content].
 */
@Composable
fun FindableDiff(find: DiffFind?, content: @Composable () -> Unit) {
    if (find == null) {
        content()
        return
    }

    val state = find.state
    val text = find.text

    RunFind(state, text, find.listState, find.source)

    val isMarking = state.isOpen || state.selectedCell != null
    val marks = if (!isMarking) null else remember(
        text, state.result, state.current, state.goToRow, state.selectedCell, state.sidewaysRevealPending,
    ) {
        DiffFindMarks.of(
            rowLines = text.rowLines,
            matches = state.result.matches,
            current = state.currentMatch,
            isSplit = text.isSplit,
            goToRow = state.goToRow,
            selectedCell = state.selectedCell,
            isSidewaysRevealPending = state.sidewaysRevealPending,
            onSidewaysRevealed = state::sidewaysRevealed,
        )
    }

    SelectableLines(state, text) {
        CompositionLocalProvider(LocalDiffFindMarks provides marks, content = content)
    }
}

/**
 * Scrolls [row] into sight, with a third of the list above it, unless it shows whole already. Rows that wrap are of any
 * height, so it counts rows rather than pixels.
 */
suspend fun LazyListState.revealRow(row: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == row }

    if (item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset) {
        return
    }

    val shown = info.visibleItemsInfo.size.coerceAtLeast(1)

    scrollToItem((row - shown / 3).coerceAtLeast(0))
}

/**
 * The lines of a view that find searches, around its list: Select all (Ctrl+A, ⌘A) selects every line, also those out
 * of sight, and Copy (Ctrl+C, ⌘C) then copies them; a press, or another key, ends it. In a split diff, the side that
 * the last press was on. The view's root takes the keys ([selectAllKeys]), so that they work wherever its focus is.
 */
@Composable
fun SelectableLines(state: FindState, text: FindableText, content: @Composable () -> Unit) {
    Box(modifier = Modifier.selectableLines(state, text)) {
        content()
    }
}

/** [SelectableLines]' presses, on a view's own container of its lines, which also make its text the one Select all takes. */
@Composable
fun Modifier.selectableLines(state: FindState, text: FindableText): Modifier {
    var width by remember { mutableIntStateOf(0) }
    val isSplit = text.isSplit

    DisposableEffect(state, text) {
        state.selectableText = text

        onDispose {
            if (state.selectableText === text) state.selectableText = null
        }
    }

    return this
        .onSizeChanged { width = it.width }
        .pointerInput(state, isSplit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)

                    if (event.type == PointerEventType.Press) {
                        state.clearSelection()

                        val position = event.changes.firstOrNull()?.position
                        if (isSplit && position != null) {
                            state.pressedCell = if (position.x < width / 2f) 0 else 1
                        }
                    }
                }
            }
        }
}

/**
 * Select all and Copy on a view's root, which sees its keys wherever the focus is in it: on the root itself, as after
 * the strip closes or when file history and blame open, in the lines, or on the header's buttons. The strip's fields keep
 * them for their own text.
 */
@Composable
fun Modifier.selectAllKeys(state: FindState): Modifier {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    return onPreviewKeyEvent { event ->
        val text = state.selectableText
        val selected = state.selectedCell

        when {
            text == null || state.isBarFocused -> false

            event.matchesBinding(KeybindingOption.SELECT_ALL) -> {
                state.selectAll(text.isSplit)
                true
            }

            selected != null && event.matchesBinding(KeybindingOption.COPY) -> {
                scope.launch { clipboard.setClipboardText(text.copyText(selected)) }
                true
            }

            selected != null && event.type == KeyEventType.KeyDown && !event.key.isModifier() -> {
                state.clearSelection()
                false
            }

            else -> false
        }
    }
}

private fun Key.isModifier(): Boolean = this in MODIFIER_KEYS

private val MODIFIER_KEYS = setOf(
    Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight,
)

/** Blame's text: the file's lines, one row each, numbered from 1. */
class BlameFindText(contents: RawText) : FindableText {
    private val lines = List(contents.size()) { contents.lineAt(it) }

    override val rows: List<FindRow> = blameFindRows(lines)
    override val lineCount: Int = lines.size
    override val isSplit = false

    override fun copyText(cell: Int): String = lines.joinToString("\n") { it.removeLineDelimiters() }
}

/** What find marks in the rows of a list whose row index is its line's, as blame's. */
class RowFindMarks(
    private val matches: Map<Int, List<FindMatch>>,
    private val current: FindMatch?,
    private val isSelected: Boolean,
    val goToRow: Int?,
) {
    fun marksOf(row: Int): LineFindMarks {
        val ranges = matches[row]?.map { it.start until it.end }.orEmpty()
        val current = current?.takeIf { it.row == row }?.let { it.start until it.end }

        return if (ranges.isEmpty() && !isSelected) LineFindMarks.NONE else LineFindMarks(ranges, current, isSelected)
    }
}

/** [state]'s marks by row, or null while find shows nothing. */
@Composable
fun rememberRowFindMarks(state: FindState): RowFindMarks? {
    if (!state.isOpen && state.selectedCell == null) return null

    return remember(state.result, state.current, state.selectedCell, state.goToRow) {
        RowFindMarks(
            matches = state.result.matches.groupBy { it.row },
            current = state.currentMatch,
            isSelected = state.selectedCell != null,
            goToRow = state.goToRow,
        )
    }
}
