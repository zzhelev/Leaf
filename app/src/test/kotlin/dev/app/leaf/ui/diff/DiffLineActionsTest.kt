// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
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
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CONTEXT = "retail.enabled=true"
private const val ADDED = "# Stubbed retail confirm endpoint"
private const val REMOVED = "logging.level=INFO"

private val contextLine = Line(CONTEXT, 86, 86, LineType.CONTEXT)
private val addedLine = Line(ADDED, 87, 87, LineType.ADDED)
private val removedLine = Line(REMOVED, 87, 88, LineType.REMOVED)

private val hunk = Hunk(
    header = "@@ -87,2 +87,2 @@",
    lines = listOf(contextLine, addedLine, removedLine),
    oldText = RawText("$CONTEXT\n$REMOVED\n".toByteArray()),
    newText = RawText("$CONTEXT\n$ADDED\n".toByteArray()),
)

private fun diffOf(entryType: EntryType, statusType: StatusType = StatusType.MODIFIED) =
    DiffType.UncommittedDiff(StatusEntry("application.properties", statusType, entryType), entryType)

/**
 * A changed line's buttons and menu in the diff pane (fork-only): Stage and Discard in Unstaged, Unstage in Staged,
 * the same in both places, and none while the hunk header has no buttons.
 */
class DiffLineActionsTest {
    private val staged = mutableListOf<Line>()
    private val discarded = mutableListOf<Line>()

    @Test
    fun `a changed line of an unstaged diff can be staged or discarded, and of a staged diff unstaged`() {
        val unstaged = diffOf(EntryType.UNSTAGED)

        assertEquals(listOf(LineAction.STAGE, LineAction.DISCARD), lineActions(addedLine, unstaged, true))
        assertEquals(listOf(LineAction.STAGE, LineAction.DISCARD), lineActions(removedLine, unstaged, true))
        assertEquals(listOf(LineAction.UNSTAGE), lineActions(addedLine, diffOf(EntryType.STAGED), true))
    }

    @Test
    fun `context lines, other files and repositories in the middle of an operation get none`() {
        assertEquals(emptyList<LineAction>(), lineActions(contextLine, diffOf(EntryType.UNSTAGED), true))
        assertEquals(emptyList<LineAction>(), lineActions(addedLine, diffOf(EntryType.UNSTAGED), false))

        for (statusType in listOf(StatusType.ADDED, StatusType.REMOVED, StatusType.CONFLICTING)) {
            assertEquals(emptyList<LineAction>(), lineActions(addedLine, diffOf(EntryType.UNSTAGED, statusType), true))
        }

        assertEquals(emptyList<LineAction>(), lineActions(addedLine, DiffType.CommitDiff(mockk()), true))
    }

    @Test
    fun `hovering an unstaged line shows Stage and Discard, which run their own action`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DiffScene(diffOf(EntryType.UNSTAGED)).use { scene ->
                scene.move(scene.text(ADDED).center)
                assertEquals(listOf("Stage line", "Discard line"), scene.buttons())

                scene.click(scene.button("Discard line").center)
                assertEquals(listOf(addedLine), discarded)
                assertEquals(emptyList<Line>(), staged)

                scene.move(scene.text(ADDED).center)
                scene.click(scene.button("Stage line").center)
                assertEquals(listOf(addedLine), staged)
            }
        }

    @Test
    fun `the buttons stay clear of the line's text`(): Unit = runBlocking(Dispatchers.Swing) {
        DiffScene(diffOf(EntryType.UNSTAGED)).use { scene ->
            scene.move(scene.text(REMOVED).center)

            assertTrue(scene.button("Discard line").right < scene.text(REMOVED).left, scene.toString())
        }
    }

    @Test
    fun `a staged line only has Unstage, and a context line nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        DiffScene(diffOf(EntryType.STAGED)).use { scene ->
            scene.move(scene.text(ADDED).center)
            assertEquals(listOf("Unstage line"), scene.buttons())

            scene.move(scene.text(CONTEXT).center)
            assertEquals(emptyList<String>(), scene.buttons())
        }
    }

    @Test
    fun `the menu has the buttons' actions`(): Unit = runBlocking(Dispatchers.Swing) {
        DiffScene(diffOf(EntryType.UNSTAGED)).use { scene ->
            scene.click(scene.text(REMOVED).center, PointerButton.Secondary)
            assertTrue(scene.texts().containsAll(listOf("Stage line", "Discard line")), scene.texts().toString())

            scene.click(scene.menuItem("Stage line").center)
            assertEquals(listOf(removedLine), staged)
        }
    }

    @Test
    fun `neither buttons nor menu while a merge or a rebase goes on`(): Unit = runBlocking(Dispatchers.Swing) {
        DiffScene(diffOf(EntryType.UNSTAGED), canUseLineActions = false).use { scene ->
            scene.move(scene.text(ADDED).center)
            assertEquals(emptyList<String>(), scene.buttons())

            scene.click(scene.text(ADDED).center, PointerButton.Secondary)
            assertTrue("Discard line" !in scene.texts(), scene.texts().toString())
        }
    }

    @Test
    fun `the split view's removed line on the left has the same buttons`(): Unit = runBlocking(Dispatchers.Swing) {
        DiffScene(diffOf(EntryType.UNSTAGED), split = true).use { scene ->
            scene.move(scene.text(REMOVED).center)
            assertEquals(listOf("Stage line", "Discard line"), scene.buttons())

            scene.click(scene.button("Discard line").center)
            assertEquals(listOf(removedLine), discarded)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private inner class DiffScene(
        diffType: DiffType,
        canUseLineActions: Boolean = true,
        split: Boolean = false,
    ) : AutoCloseable {
        private val entry = mockk<DiffEntry>(relaxed = true)

        private val scene = ImageComposeScene(width = 800, height = 300, density = Density(1f)) {
            AppTheme {
                if (split) {
                    HunkSplitTextDiff(
                        diffType = diffType,
                        scrollState = LazyListState(),
                        diffResult = DiffResult.TextSplit(
                            entry,
                            listOf(SplitHunk(hunk, listOf(contextLine to contextLine, removedLine to addedLine))),
                        ),
                        canUseHunkActions = canUseLineActions,
                        onUnstageHunk = { _, _ -> },
                        onStageHunk = { _, _ -> },
                        onResetHunk = { _, _ -> },
                        onUnStageLine = { _, _, line -> staged += line },
                        onDiscardLine = { _, _, line -> discarded += line },
                    )
                } else {
                    HunkUnifiedTextDiff(
                        diffType = diffType,
                        scrollState = LazyListState(),
                        diffResult = DiffResult.Text(entry, listOf(hunk)),
                        canUseHunkActions = canUseLineActions,
                        onUnstageHunk = { _, _ -> },
                        onStageHunk = { _, _ -> },
                        onUnStageLine = { _, _, line -> staged += line },
                        onResetHunk = { _, _ -> },
                        onDiscardLine = { _, _, line -> discarded += line },
                    )
                }
            }
        }

        init {
            render()
        }

        fun render() {
            scene.render()
            scene.render()
        }

        fun move(position: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, position)
            render()
        }

        suspend fun click(position: Offset, button: PointerButton = PointerButton.Primary) {
            val pressed = if (button == PointerButton.Secondary) {
                // A menu ignores a right-click that comes within 20 ms of the last one, in any test
                delay(50)
                PointerButtons(isSecondaryPressed = true)
            } else {
                PointerButtons(isPrimaryPressed = true)
            }

            scene.sendPointerEvent(PointerEventType.Move, position)
            scene.sendPointerEvent(PointerEventType.Press, position, buttons = pressed, button = button)
            scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = button)
            render()
        }

        /** The descriptions of the buttons shown, in order from the left. */
        fun buttons(): List<String> = nodes()
            .filter { node -> node.descriptions().any { it in LINE_ACTION_LABELS } }
            .sortedBy { it.boundsInWindow.left }
            .flatMap { it.descriptions() }

        fun button(description: String): Rect = nodes()
            .first { description in it.descriptions() }
            .boundsInWindow

        /** A line's text, on the left side in the split view. */
        fun text(text: String): Rect = nodes()
            .filter { node -> node.texts().any { it.startsWith(text) } }
            .minBy { it.boundsInWindow.left }
            .boundsInWindow

        fun menuItem(label: String): Rect = nodes()
            .last { label in it.texts() }
            .boundsInWindow

        fun texts(): List<String> = nodes().flatMap { it.texts() }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

        private fun SemanticsNode.descriptions() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

        override fun toString() = nodes().map { "${it.texts()}${it.descriptions()} ${it.boundsInWindow}" }.toString()

        override fun close() = scene.close()
    }
}

private val LINE_ACTION_LABELS = listOf("Stage line", "Unstage line", "Discard line")
