// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package dev.app.leaf.ui.find

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.SplitHunk
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.SidewaysScroll
import dev.app.leaf.ui.diff.DiffLines
import dev.app.leaf.ui.diff.HunkSplitTextDiff
import dev.app.leaf.ui.diff.HunkUnifiedTextDiff
import dev.app.leaf.ui.diff.lineTextStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.jetbrains.skia.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

internal val isMac = currentOs == OS.MAC

/** The command key of this system: Cmd on a Mac, Ctrl elsewhere. */
internal fun command(key: Key, shift: Boolean = false, type: KeyEventType = KeyEventType.KeyDown) =
    KeyEvent(key, type, isCtrlPressed = !isMac, isMetaPressed = isMac, isShiftPressed = shift)

internal fun plain(key: Key, shift: Boolean = false, type: KeyEventType = KeyEventType.KeyDown) =
    KeyEvent(key, type, isShiftPressed = shift)

/** Go to line, Ctrl+G (⌃G) on every system. */
internal val goToLineKey = KeyEvent(Key.G, KeyEventType.KeyDown, isCtrlPressed = true)

/** The file of the tests: 400 lines, of which the new version has "parser" five times, at lines 6, 8 and 300. */
internal const val FILE_LINES = 400

internal fun newText(index: Int): String = when (index) {
    5 -> "val parser = Parser()"
    7 -> "parser.parser()"
    299 -> "// parser here"
    else -> "line ${index + 1}"
}

private fun oldText(index: Int): String = if (index == 5) "val old = 0" else newText(index)

private val oldRaw = RawText((0 until FILE_LINES).joinToString("") { oldText(it) + "\n" }.toByteArray())
private val newRaw = RawText((0 until FILE_LINES).joinToString("") { newText(it) + "\n" }.toByteArray())

private val entry = object : DiffEntry() {
    init {
        changeType = ChangeType.MODIFY
        oldPath = "src/Parser.kt"
        newPath = "src/Parser.kt"
    }
}

internal val commitDiff = DiffType.CommitDiff(entry)

/** Index 5 was replaced; every other line is unchanged. */
private fun linesOf(indexes: IntRange): List<Line> = indexes.flatMap { index ->
    if (index == 5) {
        listOf(Line(oldText(5) + "\n", 5, 5, LineType.REMOVED), Line(newText(5) + "\n", 5, 5, LineType.ADDED))
    } else {
        listOf(Line(newText(index) + "\n", index, index, LineType.CONTEXT))
    }
}

private fun hunk(indexes: IntRange) = Hunk("@@ ${indexes.first} @@", linesOf(indexes), oldRaw, newRaw)

/** The hunks view: lines 1 to 10 and 296 to 305. */
internal val hunksDiff = DiffResult.Text(entry, listOf(hunk(0..9), hunk(295..304)))

/** The complete file, in one hunk. */
internal val fullDiff = DiffResult.Text(entry, listOf(hunk(0 until FILE_LINES)))

internal fun split(diff: DiffResult.Text) = DiffResult.TextSplit(
    entry,
    diff.hunks.map { hunk ->
        val pairs = ArrayList<Pair<Line?, Line?>>()
        var index = 0

        while (index < hunk.lines.size) {
            val line = hunk.lines[index]

            if (line.lineType == LineType.REMOVED) {
                pairs.add(line to hunk.lines[index + 1])
                index += 2
            } else {
                pairs.add(line to line)
                index++
            }
        }

        SplitHunk(hunk, pairs)
    },
)

internal class RecordingClipboard : Clipboard {
    @Volatile
    var text: String? = null

    override val nativeClipboard: NativeClipboard get() = error("Not used")

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}

/**
 * A view that find searches, in a tab: the tab's key handler sends the find keys to [targets] and Esc to its strip, as
 * `RepositoryOpenPage` does. [viewContent] draws the view's strip and lines, set once a subclass has its own fields.
 */
internal open class FindScene(
    width: Int = 900,
    height: Int = 400,
    theme: Theme = Theme.Dark,
    style: StyleDefinition = BuiltInStyles.DEFAULT,
) : AutoCloseable {
    protected var viewContent by mutableStateOf<(@Composable () -> Unit)?>(null)
    val targets = FindTargets()
    val clipboard = RecordingClipboard()
    val root = FocusRequester()
    var logSearches = 0
    var isRootFocused = false
    private var frameTime = 0L

    val scene = ImageComposeScene(width = width, height = height, density = Density(1f), coroutineContext = Dispatchers.Swing) {
        AppTheme(selectedTheme = theme, style = style) {
            CompositionLocalProvider(LocalFindTargets provides targets, LocalClipboard provides clipboard) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(root)
                        .onFocusChanged { isRootFocused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            targets.onKeyEvent(event, onFindInLog = { logSearches++ }) ||
                                (event.matchesBinding(KeybindingOption.EXIT) && targets.closeFocusedFind())
                        },
                ) {
                    viewContent?.invoke()
                }
            }
        }
    }

    suspend fun settle() = repeat(10) {
        scene.render(frameTime)
        frameTime += 16_000_000L
        delay(10)
    }

    suspend fun press(event: KeyEvent) {
        scene.sendKeyEvent(event)
        scene.sendKeyEvent(KeyEvent(event.key, KeyEventType.KeyUp, isCtrlPressed = event.isCtrlPressed, isMetaPressed = event.isMetaPressed, isShiftPressed = event.isShiftPressed))
        settle()
    }

    suspend fun click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }

        return nodes
    }

    fun texts(): List<String> = nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun field(description: String): SemanticsNode = nodes().first { node ->
        node.config.contains(SemanticsProperties.EditableText) &&
            description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    fun fieldWidth(fieldDescription: String): Float = field(fieldDescription).boundsInRoot.width

    fun isFocused(fieldDescription: String) = field(fieldDescription).config.getOrNull(SemanticsProperties.Focused) == true

    fun hasField(description: String) = nodes().any { node ->
        node.config.contains(SemanticsProperties.EditableText) &&
            description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    suspend fun type(fieldDescription: String, text: String) {
        checkNotNull(field(fieldDescription).config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
        settle()
    }

    /** Clicks the button whose description starts with [description], the key it names after it. */
    suspend fun clickButton(description: String) {
        val node = nodes().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(description) }
        }

        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        settle()
    }

    fun image(): Image = scene.render(frameTime)

    /** Every pixel of the rendered scene. */
    fun pixels(): IntArray {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image())

        return IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
    }

    fun differingPixels(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }

    /** How many pixels of the rendered scene have [color]. */
    fun pixelsOf(color: Color): Int {
        val image = image()
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val argb = color.value.shr(32).toInt()
        var count = 0

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getColor(x, y) == argb) count++
            }
        }

        return count
    }

    override fun close() = scene.close()
}

/** A diff with find, as the diff pane has it: the strip under a header, and the lines below. */
internal class DiffFindScene(
    diff: DiffResult.TextDiff,
    wrap: Boolean = true,
    width: Int = 900,
    height: Int = 400,
    theme: Theme = Theme.Dark,
    style: StyleDefinition = BuiltInStyles.DEFAULT,
) : FindScene(width, height, theme, style) {
    var diff by mutableStateOf(diff)
    val find = FindState()
    val listState = LazyListState()
    val sideways: SidewaysScroll? = if (wrap) null else SidewaysScroll()
    var completeFileRequests = 0

    /** The view's own frame, focusable as the diff pane's is, which the strip gives the focus to when it closes. */
    val view = FocusRequester()

    /** The view, or something in it, has the focus. */
    var isViewFocused = false

    private val content: @Composable () -> Unit = {
        val shown = this@DiffFindScene.diff

        if (!isHidden) Lines(shown)
    }

    /** The diff shows a file without lines, as a binary one, which takes the strip and the lines away. */
    var isHidden by mutableStateOf(false)

    @Composable
    private fun Lines(shown: DiffResult.TextDiff) {
        val text = remember(shown) { DiffFindText(shown) }

        Column(
            Modifier
                .focusable()
                .focusRequester(view)
                .onFocusChanged { isViewFocused = it.hasFocus }
                .findableView(find),
        ) {
            FindTarget(find, isAvailable = true)

            if (find.isOpen) {
                FindBar(
                    state = find,
                    lineCount = text.lineCount,
                    onGoToLine = { line ->
                        find.goToLine(line, text, onShowCompleteFile = {
                            completeFileRequests++
                            this@DiffFindScene.diff = if (shown is DiffResult.TextSplit) split(fullDiff) else fullDiff
                        })
                    },
                    focusAfterClose = view,
                )
            }

            // Another diff draws its lines anew, as the diff pane does from the unified to the split view
            key(shown) {
                DiffLines(sideways, shown, lineTextStart(commitDiff), DiffFind(find, text, listState, source = "src/Parser.kt")) {
                    when (shown) {
                        is DiffResult.Text -> HunkUnifiedTextDiff(
                            commitDiff, listState, shown, false, { _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> },
                            { _, _, _ -> },
                        )

                        is DiffResult.TextSplit -> HunkSplitTextDiff(
                            commitDiff, listState, shown, false, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _, _ -> },
                            { _, _, _ -> },
                        )
                    }
            }
            }
        }
    }

    init {
        viewContent = content
    }

    /** The rows that the list shows whole. */
    fun visibleRows(): List<Int> {
        val info = listState.layoutInfo

        return info.visibleItemsInfo
            .filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
            .map { it.index }
    }
}
