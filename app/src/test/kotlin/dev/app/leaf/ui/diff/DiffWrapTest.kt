// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.SplitHunk
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.SidewaysScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val WIDTH = 800
private const val SHORT = "short"
private val LONG = "a long value that goes on, ".repeat(12).trim()

private val contextLine = Line("context", 6, 6, LineType.CONTEXT)
private val longLine = Line(LONG, 7, 7, LineType.ADDED)
private val shortLine = Line(SHORT, 7, 8, LineType.ADDED)

private val hunk = Hunk(
    header = "@@ -7,1 +7,3 @@",
    lines = listOf(contextLine, longLine, shortLine),
    oldText = RawText("context\n".toByteArray()),
    newText = RawText("context\n$LONG\n$SHORT\n".toByteArray()),
)

private val entry = object : DiffEntry() {
    init {
        changeType = ChangeType.MODIFY
        oldPath = "notes.txt"
        newPath = "notes.txt"
    }
}

/** An unstaged file: its lines have Stage and Discard, so the text starts 40 dp in. */
private val unstaged = DiffType.UncommittedDiff(StatusEntry("notes.txt", StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED)

private val unified = DiffResult.Text(entry, listOf(hunk))
private val split = DiffResult.TextSplit(
    entry,
    listOf(SplitHunk(hunk, listOf(contextLine to contextLine, null to longLine, null to shortLine))),
)

/**
 * The diff pane's "Wrap long lines" (fork-only): wrapped, the lines are as before; turned off, each stays on one row,
 * and they all scroll sideways together, in the unified and the split view, while the line numbers stay put.
 */
class DiffWrapTest {
    @Test
    fun `the longest line is measured as the diff shows it`() {
        assertEquals("", longestDiffLine(emptySequence()))
        assertEquals(
            "    tabbed",
            longestDiffLine(sequenceOf(Line("short", 0, 0, LineType.ADDED), Line("\ttabbed\r\n", 1, 1, LineType.ADDED))),
        )

        val huge = longestDiffLine(sequenceOf(Line("x".repeat(MAX_DIFF_LINE_CHARACTERS + 5), 0, 0, LineType.ADDED)))

        assertTrue(huge.startsWith("x".repeat(MAX_DIFF_LINE_CHARACTERS) + " … "), huge.takeLast(40))
        assertTrue(huge.length < MAX_DIFF_LINE_CHARACTERS + 40)
    }

    @Test
    fun `line numbers are as wide as the highest of any hunk`() {
        assertEquals(1, lineNumberDigits(emptyList()))
        assertEquals(1, lineNumberDigits(listOf(hunk)))

        val far = hunk.copy(lines = listOf(Line("x", 1233, 1499, LineType.CONTEXT)))

        assertEquals(4, lineNumberDigits(listOf(hunk, far)))
        assertEquals(4, lineNumberDigits(listOf(far, hunk)))
    }

    @Test
    fun `unified, a long line wraps, and turned off it scrolls sideways with the others`(): Unit =
        runBlocking(Dispatchers.Swing) {
            checkSideways(unified)
        }

    @Test
    fun `split, both sides scroll together, and the new side's longest line reaches its end`(): Unit =
        runBlocking(Dispatchers.Swing) {
            checkSideways(split)
        }

    @Test
    fun `the header's button turns wrapping off, and on again`(): Unit = runBlocking(Dispatchers.Swing) {
        for (wrap in listOf(true, false)) {
            var changedTo: Boolean? = null
            val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
                AppTheme { WrapLinesButton(wrap, onWrapLinesChanged = { changedTo = it }) }
            }

            try {
                scene.render()
                val button = scene.nodes().single { it.config.getOrNull(SemanticsActions.OnClick) != null }
                checkNotNull(button.config[SemanticsActions.OnClick].action).invoke()

                assertEquals(!wrap, changedTo, "wrap = $wrap")
            } finally {
                scene.close()
            }
        }
    }

    private fun checkSideways(diff: DiffResult.TextDiff) {
        var wrap by mutableStateOf(true)
        var sideways: SidewaysScroll? = null

        val scene = ImageComposeScene(width = WIDTH, height = 300, density = Density(1f)) {
            AppTheme {
                val scroll = remember(wrap) { if (wrap) null else SidewaysScroll() }
                sideways = scroll

                DiffLines(scroll, diff, lineTextStart(unstaged)) {
                    when (diff) {
                        is DiffResult.Text -> HunkUnifiedTextDiff(
                            unstaged, rememberLazyListState(), diff, true, { _, _ -> }, { _, _ -> },
                            { _, _, _ -> }, { _, _ -> }, { _, _, _ -> },
                        )

                        is DiffResult.TextSplit -> HunkSplitTextDiff(
                            unstaged, rememberLazyListState(), diff, true, { _, _ -> }, { _, _ -> },
                            { _, _ -> }, { _, _, _ -> }, { _, _, _ -> },
                        )
                    }
                }
            }
        }

        try {
            scene.frames()

            // Wrapped: the long line is taller than the short one, and nothing scrolls
            assertNull(sideways)
            val shortHeight = scene.placeOf(SHORT).height
            assertTrue(scene.placeOf(LONG).height > shortHeight * 1.5f, "${scene.placeOf(LONG)}")

            wrap = false
            scene.frames()

            val scroll = checkNotNull(sideways)
            val long = scene.placeOf(LONG)
            val short = scene.placeOf(SHORT)
            val number = scene.placeOf("8")

            // One line high, and wider than the pane
            assertEquals(shortHeight, long.height)
            assertTrue(long.width > WIDTH, "$long")
            assertTrue(scroll.max > 0f)
            // Its row is one line high too: the short line follows a line further down
            assertEquals(long.top + shortHeight, short.top, 1f)

            // Two sideways steps of 48 dp move every line, and leave the line numbers where they are
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 60f), scrollDelta = Offset(2f, 0f))
            scene.frames()

            assertEquals(96f, scroll.offset)
            assertEquals(long.left - 96f, scene.placeOf(LONG).left)
            assertEquals(short.left - 96f, scene.placeOf(SHORT).left)
            assertEquals(number, scene.placeOf("8"))

            // Shift and the wheel go sideways too
            val shift = PointerKeyboardModifiers(isShiftPressed = true)
            scene.sendPointerEvent(
                PointerEventType.Scroll, Offset(300f, 60f), scrollDelta = Offset(0f, 1f), keyboardModifiers = shift,
            )
            scene.frames()

            assertEquals(144f, scroll.offset)

            // At the far end, the longest line ends 24 dp before the pane's edge
            scroll.scrollTo(Float.MAX_VALUE)
            scene.frames()

            val end = scene.placeOf(LONG)
            assertEquals(WIDTH - 24f, end.left + end.width, 2f, "$end, max ${scroll.max}")

            // And back past the start stops at it
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 60f), scrollDelta = Offset(-1000f, 0f))
            scene.frames()

            assertEquals(0f, scroll.offset)
            assertEquals(long.left, scene.placeOf(LONG).left)

            // Wrapped again, as before
            wrap = true
            scene.frames()

            assertNull(sideways)
            assertTrue(scene.placeOf(LONG).height > shortHeight * 1.5f)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> =
        semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }

    /** Where the one node with [text] is laid out, unclipped. */
    private fun ImageComposeScene.placeOf(text: String): Rect = nodes()
        .single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        .let { Rect(it.positionInRoot, it.size.toSize()) }

    private var frameTime = 0L

    private fun ImageComposeScene.frames(count: Int = 10) {
        repeat(count) {
            frameTime += 16_000_000L
            render(frameTime)
        }
    }
}
