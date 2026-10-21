// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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
import dev.app.leaf.domain.models.DiffWhitespaceMode
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.SplitHunk
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.sort.SortMenuItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val labels = DiffViewMenuLabels("Ignore", "ignore hint", "Show", "show hint", "Wrap", "wrap hint")

private fun uncommitted(entryType: EntryType, statusType: StatusType = StatusType.MODIFIED) =
    DiffType.UncommittedDiff(StatusEntry("src/Parser.kt", statusType, entryType), entryType)

private fun committed(changeType: DiffEntry.ChangeType) = DiffType.CommitDiff(
    mockk<DiffEntry>(relaxed = true) {
        every { this@mockk.changeType } returns changeType
        every { oldPath } returns "src/Parser.kt"
        every { newPath } returns "src/Parser.kt"
    }
)

private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

private fun SemanticsNode.descriptions() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

/** The diff's View menu, the strip that says whitespace is ignored, and the pane of a file that only changed in it. */
@OptIn(ExperimentalComposeUiApi::class)
class DiffViewMenuTest {
    private val ignored = mutableListOf<Boolean>()
    private val marked = mutableListOf<Boolean>()
    private val wrapped = mutableListOf<Boolean>()

    private fun items(
        options: DiffViewOptions = DiffViewOptions(ignoreWhitespace = false, showWhitespace = false, wrapLines = true),
        showsIgnore: Boolean = true,
        canWrap: Boolean = true,
    ) = diffViewMenuItems(
        showsIgnoreWhitespace = showsIgnore,
        options = options,
        labels = labels,
        onIgnoreWhitespaceChanged = { ignored += it },
        onShowWhitespaceChanged = { marked += it },
        onWrapLinesChanged = if (canWrap) { wrap -> wrapped += wrap } else null,
    ).filterIsInstance<SortMenuItem.Option>()

    @Test
    fun `the menu has the three switches, set as the options are`() {
        val rows = items(DiffViewOptions(ignoreWhitespace = true, showWhitespace = false, wrapLines = true))

        assertEquals(listOf("Ignore", "Show", "Wrap"), rows.map { it.label })
        assertEquals(listOf(true, false, true), rows.map { it.isChecked })
        assertEquals(listOf("ignore hint", "show hint", "wrap hint"), rows.map { it.hint })
    }

    @Test
    fun `a row turns its switch the other way, and keeps the menu open`() {
        val rows = items(DiffViewOptions(ignoreWhitespace = true, showWhitespace = false, wrapLines = true))

        rows.forEach { it.onClick() }

        assertEquals(listOf(false), ignored)
        assertEquals(listOf(true), marked)
        assertEquals(listOf(false), wrapped)
        assertEquals(listOf(false, false, false), rows.map { it.closesMenu }, "Two switches can be set in one go")
    }

    @Test
    fun `an added or a deleted file can't ignore whitespace, and file history has no wrap switch`() {
        assertEquals(listOf("Show", "Wrap"), items(showsIgnore = false).map { it.label })
        assertEquals(listOf("Ignore", "Show"), items(canWrap = false).map { it.label })

        assertTrue(canIgnoreWhitespace(uncommitted(EntryType.UNSTAGED)))
        assertTrue(canIgnoreWhitespace(uncommitted(EntryType.STAGED, StatusType.MODIFIED)))
        assertTrue(canIgnoreWhitespace(committed(DiffEntry.ChangeType.MODIFY)))
        assertTrue(canIgnoreWhitespace(committed(DiffEntry.ChangeType.RENAME)))
        assertTrue(!canIgnoreWhitespace(uncommitted(EntryType.UNSTAGED, StatusType.ADDED)))
        assertTrue(!canIgnoreWhitespace(uncommitted(EntryType.STAGED, StatusType.REMOVED)))
        assertTrue(!canIgnoreWhitespace(committed(DiffEntry.ChangeType.ADD)))
        assertTrue(!canIgnoreWhitespace(committed(DiffEntry.ChangeType.DELETE)))
    }

    @Test
    fun `the switch of the menu stands for ignoring all whitespace`() {
        assertEquals(DiffWhitespaceMode.IGNORE_ALL, true.toWhitespaceMode())
        assertEquals(DiffWhitespaceMode.SHOW_ALL, false.toWhitespaceMode())
    }

    // The button

    @Test
    fun `the button is plain until a switch differs from how a diff first looks, and its tooltip says which`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val plain = ViewScene(DiffViewOptions(false, false, true))
            val expected = mapOf(
                DiffViewOptions(false, false, true) to "View options",
                DiffViewOptions(true, false, true) to "View options (ignoring whitespace)",
                DiffViewOptions(false, true, true) to "View options (showing whitespace characters)",
                DiffViewOptions(false, false, false) to "View options (lines not wrapped)",
                DiffViewOptions(true, true, false) to
                    "View options (ignoring whitespace, showing whitespace characters, lines not wrapped)",
            )

            try {
                val plainPixels = plain.pixels()

                for ((options, tooltip) in expected) {
                    plain.options = options
                    plain.render()

                    assertEquals(tooltip, plain.descriptions().single { it.startsWith("View options") }, "$options")

                    if (options == DiffViewOptions(false, false, true)) {
                        assertEquals(plainPixels, plain.pixels(), "Not lit")
                    } else {
                        assertNotEquals(plainPixels, plain.pixels(), "Lit: $options")
                    }
                }
            } finally {
                plain.close()
            }
        }

    @Test
    fun `file history's button doesn't count wrapping, as it has no switch for it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            ViewScene(DiffViewOptions(false, false, wrapLines = false), canWrap = false).use { scene ->
                assertEquals("View options", scene.descriptions().single { it.startsWith("View options") })
            }
        }

    @Test
    fun `an added file's button doesn't count ignoring whitespace, which it has no row for`(): Unit =
        runBlocking(Dispatchers.Swing) {
            ViewScene(
                DiffViewOptions(ignoreWhitespace = true, showWhitespace = false, wrapLines = true),
                diffType = uncommitted(EntryType.UNSTAGED, StatusType.ADDED),
            ).use { scene ->
                assertEquals("View options", scene.descriptions().single { it.startsWith("View options") })
            }
        }

    @Test
    fun `clicking the button opens the menu, and a row sets its switch`(): Unit = runBlocking(Dispatchers.Swing) {
        ViewScene(DiffViewOptions(false, false, true)).use { scene ->
            scene.click(scene.node { it.descriptions().any { d -> d.startsWith("View options") } }.boundsInWindow.center)

            val texts = scene.texts()
            assertTrue(
                texts.containsAll(listOf("Ignore whitespace", "Show whitespace characters", "Wrap long lines")),
                texts.toString(),
            )

            scene.click(scene.node { "Show whitespace characters" in it.texts() }.boundsInWindow.center)
            assertEquals(listOf(true), marked)
            assertEquals(emptyList<Boolean>(), ignored)
        }
    }

    // The strip and the pane

    @Test
    fun `the strip says that whitespace is ignored, and where the buttons went, and shows them again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var shown = 0

            ContentScene { WhitespaceIgnoredStrip(actionsOff = "Stage and discard are off.", onShowThem = { shown++ }) }.use { scene ->
                assertTrue("Ignoring whitespace changes. Stage and discard are off." in scene.texts(), scene.texts().toString())

                scene.click(scene.node { "Show them" in it.texts() }.boundsInWindow.center)
                assertEquals(1, shown)
            }

            ContentScene { WhitespaceIgnoredStrip(actionsOff = null, onShowThem = {}) }.use { scene ->
                assertTrue("Ignoring whitespace changes." in scene.texts(), scene.texts().toString())
            }
        }

    @Test
    fun `the strip names the buttons that are off for each kind of diff`(): Unit = runBlocking(Dispatchers.Swing) {
        val said = mutableMapOf<String, String?>()

        for ((name, diffType) in listOf(
            "unstaged" to uncommitted(EntryType.UNSTAGED),
            "staged" to uncommitted(EntryType.STAGED),
            "commit" to committed(DiffEntry.ChangeType.MODIFY),
            "added" to uncommitted(EntryType.UNSTAGED, StatusType.ADDED),
        )) {
            ContentScene { said[name] = whitespaceActionsOffText(diffType) }.use { }
        }

        assertEquals(
            mapOf(
                "unstaged" to "Stage and discard are off.",
                "staged" to "Unstage is off.",
                "commit" to null,
                "added" to null,
            ),
            said,
        )
    }

    @Test
    fun `a file that only changed whitespace says so, and offers the changes`(): Unit = runBlocking(Dispatchers.Swing) {
        var shown = 0

        ContentScene { OnlyWhitespaceChanged(onShowChanges = { shown++ }) }.use { scene ->
            assertTrue(
                scene.texts().containsAll(
                    listOf(
                        "Only whitespace changed in this file",
                        "The diff is hiding whitespace changes.",
                        "Show whitespace changes",
                    )
                ),
                scene.texts().toString(),
            )

            scene.click(scene.node { "Show whitespace changes" in it.texts() }.boundsInWindow.center)
            assertEquals(1, shown)
        }
    }

    // A hunk of a diff that ignored whitespace has no buttons

    private val removed = Line("old\n", 4, 4, LineType.REMOVED)
    private val added = Line("new\n", 4, 4, LineType.ADDED)
    private val context = Line("same\n", 3, 3, LineType.CONTEXT)

    private fun hunkOf(ignoresWhitespace: Boolean) = Hunk(
        "@@ -4 +4 @@",
        listOf(context, removed, added),
        RawText("same\nold\n".toByteArray()),
        RawText("same\nnew\n".toByteArray()),
        ignoresWhitespace,
    )

    @Test
    fun `an unstaged hunk has its buttons, unless it was made ignoring whitespace`(): Unit = runBlocking(Dispatchers.Swing) {
        for (split in listOf(false, true)) {
            val unstaged = uncommitted(EntryType.UNSTAGED)

            HunkScene(unstaged, hunkOf(ignoresWhitespace = false), split).use { scene ->
                assertEquals(listOf("Discard hunk", "Stage hunk"), scene.hunkButtons(), "split=$split")

                scene.move(scene.text("new").center)
                assertEquals(listOf("Stage line", "Discard line"), scene.lineButtons(), "split=$split")
            }

            HunkScene(unstaged, hunkOf(ignoresWhitespace = true), split).use { scene ->
                assertEquals(emptyList<String>(), scene.hunkButtons(), "split=$split")

                scene.move(scene.text("new").center)
                assertEquals(emptyList<String>(), scene.lineButtons(), "split=$split")

                scene.click(scene.text("new").center, PointerButton.Secondary)
                assertTrue("Stage line" !in scene.texts(), "No menu item either: ${scene.texts()}")
            }
        }
    }

    @Test
    fun `a staged hunk made ignoring whitespace has no Unstage`(): Unit = runBlocking(Dispatchers.Swing) {
        val staged = uncommitted(EntryType.STAGED)

        HunkScene(staged, hunkOf(ignoresWhitespace = false), split = false).use { scene ->
            assertEquals(listOf("Unstage hunk"), scene.hunkButtons())
        }

        HunkScene(staged, hunkOf(ignoresWhitespace = true), split = false).use { scene ->
            assertEquals(emptyList<String>(), scene.hunkButtons())
        }
    }

    // Scenes

    private abstract inner class Scene : AutoCloseable {
        protected abstract val scene: ImageComposeScene

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

        fun node(predicate: (SemanticsNode) -> Boolean): SemanticsNode = nodes().first(predicate)

        fun texts(): List<String> = nodes().flatMap { it.texts() }

        fun descriptions(): List<String> = nodes().flatMap { it.descriptions() }

        fun pixels(): List<Int> {
            val bitmap = Bitmap.makeFromImage(scene.render())

            return (0 until bitmap.width * bitmap.height).map { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }

    private inner class ContentScene(content: @Composable () -> Unit) : Scene() {
        override val scene = ImageComposeScene(width = 600, height = 200, density = Density(1f)) {
            AppTheme { Box(Modifier.fillMaxSize()) { content() } }
        }

        init {
            render()
        }
    }

    /** The View button on its own, at the right of a header-coloured box, as the header has it. */
    private inner class ViewScene(
        options: DiffViewOptions,
        private val diffType: DiffType = uncommitted(EntryType.UNSTAGED),
        private val canWrap: Boolean = true,
    ) : Scene() {
        var options by mutableStateOf(options)

        override val scene = ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            AppTheme {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0A335C))) {
                    DiffViewMenu(
                        diffType = diffType,
                        options = this@ViewScene.options,
                        onIgnoreWhitespaceChanged = { ignored += it },
                        onShowWhitespaceChanged = { marked += it },
                        onWrapLinesChanged = if (canWrap) { wrap -> wrapped += wrap } else null,
                    )
                }
            }
        }

        init {
            render()
        }
    }

    private inner class HunkScene(diffType: DiffType, hunk: Hunk, split: Boolean) : Scene() {
        private val entry = mockk<DiffEntry>(relaxed = true)

        override val scene = ImageComposeScene(width = 800, height = 300, density = Density(1f)) {
            AppTheme {
                if (split) {
                    HunkSplitTextDiff(
                        diffType = diffType,
                        scrollState = LazyListState(),
                        diffResult = DiffResult.TextSplit(
                            entry,
                            listOf(SplitHunk(hunk, listOf(context to context, removed to added))),
                        ),
                        canUseHunkActions = true,
                        onUnstageHunk = { _, _ -> },
                        onStageHunk = { _, _ -> },
                        onResetHunk = { _, _ -> },
                        onUnStageLine = { _, _, _ -> },
                        onDiscardLine = { _, _, _ -> },
                    )
                } else {
                    HunkUnifiedTextDiff(
                        diffType = diffType,
                        scrollState = LazyListState(),
                        diffResult = DiffResult.Text(entry, listOf(hunk)),
                        canUseHunkActions = true,
                        onUnstageHunk = { _, _ -> },
                        onStageHunk = { _, _ -> },
                        onUnStageLine = { _, _, _ -> },
                        onResetHunk = { _, _ -> },
                        onDiscardLine = { _, _, _ -> },
                    )
                }
            }
        }

        init {
            render()
        }

        fun hunkButtons(): List<String> = texts().filter { it in listOf("Stage hunk", "Unstage hunk", "Discard hunk") }
            .sorted()

        fun lineButtons(): List<String> = descriptions().filter { it in listOf("Stage line", "Unstage line", "Discard line") }

        fun text(text: String): Rect = node { n -> n.texts().any { it.startsWith(text) } }.boundsInWindow
    }
}
