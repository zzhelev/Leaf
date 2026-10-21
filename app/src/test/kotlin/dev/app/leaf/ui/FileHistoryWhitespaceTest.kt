// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.DiffWhitespaceMode
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.diff.WhitespaceViewOptions
import dev.app.leaf.viewmodels.HistoryState
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** File history's View menu and the strip that says whitespace is ignored (fork-only). */
@OptIn(ExperimentalComposeUiApi::class)
class FileHistoryWhitespaceTest {
    private val author = Identity("Maya Chen", "maya.chen@example.com")
    private val commits = listOf("a", "b").map { Commit(it.repeat(40), "Commit $it", author, author, 0L, emptyList()) }

    private val ignoreChanges = mutableListOf<DiffWhitespaceMode>()
    private val showChanges = mutableListOf<Boolean>()

    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private val entry = mockk<DiffEntry>(relaxed = true) {
        every { changeType } returns DiffEntry.ChangeType.MODIFY
        every { oldPath } returns "src/App.kt"
        every { newPath } returns "src/App.kt"
    }

    private fun render(
        diff: DiffResult.TextDiff,
        whitespace: DiffWhitespaceMode,
        showWhitespace: Boolean = false,
        withMenu: Boolean = true,
    ) {
        scene?.close()
        scene = ImageComposeScene(width = 900, height = 400, density = Density(1f)) {
            AppTheme {
                HistoryContentLoaded(
                    historyState = HistoryState.Loaded("src/App.kt", commits),
                    viewDiffResult = ViewDiffResult.Loaded(DiffType.CommitDiff(entry), diff),
                    scrollState = LazyListState(),
                    selectedCommit = commits[0],
                    onCommitSelected = {},
                    onSelectAdjacentCommit = {},
                    whitespace = if (withMenu) {
                        WhitespaceViewOptions(
                            whitespace = whitespace,
                            showWhitespace = showWhitespace,
                            onWhitespaceChanged = { ignoreChanges += it },
                            onShowWhitespaceChanged = { showChanges += it },
                        )
                    } else {
                        null
                    },
                )
            }
        }

        repeat(3) { scene!!.render(it * 16_000_000L) }
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }

        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

        return nodes
    }

    private fun texts() = nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun descriptions() =
        nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun click(text: String) {
        val position = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }.boundsInWindow.center

        scene!!.sendPointerEvent(PointerEventType.Move, position)
        scene!!.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene!!.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Primary)
        repeat(2) { scene!!.render() }
    }

    @Test
    fun `the commit strip has the View menu, lit while whitespace is ignored, and no wrap row`() {
        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.SHOW_ALL)
        assertTrue("View options" in descriptions(), descriptions().toString())

        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.IGNORE_ALL)
        assertTrue("View options (ignoring whitespace)" in descriptions(), descriptions().toString())

        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.IGNORE_CHANGE)
        assertTrue("View options (ignoring different amounts of whitespace)" in descriptions(), descriptions().toString())
    }

    @Test
    fun `the strip says which way whitespace is ignored`() {
        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.IGNORE_CHANGE)

        assertTrue("Ignoring different amounts of whitespace." in texts(), texts().toString())
        assertTrue("Ignoring whitespace changes." !in texts())

        click("Show them")
        assertEquals(listOf(DiffWhitespaceMode.SHOW_ALL), ignoreChanges)
    }

    @Test
    fun `a strip under the commit says whitespace is ignored, and shows the changes with one click`() {
        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.IGNORE_ALL)

        // There are no buttons to turn off in file history
        assertTrue("Ignoring whitespace changes." in texts(), texts().toString())

        click("Show them")
        assertEquals(listOf(DiffWhitespaceMode.SHOW_ALL), ignoreChanges)
    }

    @Test
    fun `no strip while whitespace shows, and none without the switches`() {
        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.SHOW_ALL)
        assertTrue("Show them" !in texts())

        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.IGNORE_ALL, withMenu = false)
        assertTrue("Show them" !in texts())
        assertTrue(descriptions().none { it.startsWith("View options") })
    }

    @Test
    fun `a commit that only changed whitespace in the file says so, and offers the changes`() {
        render(DiffResult.Text(entry, emptyList(), onlyWhitespaceChanged = true), whitespace = DiffWhitespaceMode.IGNORE_ALL)

        assertTrue("Only whitespace changed in this file" in texts(), texts().toString())

        click("Show whitespace changes")
        assertEquals(listOf(DiffWhitespaceMode.SHOW_ALL), ignoreChanges)
    }

    private fun pixelsWithMarks(showWhitespace: Boolean): List<Int> {
        val line = Line("x = a  b \n", 0, 0, LineType.CONTEXT)
        val hunk = Hunk("@@ -1 +1 @@", listOf(line), RawText.EMPTY_TEXT, RawText.EMPTY_TEXT)

        render(DiffResult.Text(entry, listOf(hunk)), whitespace = DiffWhitespaceMode.SHOW_ALL, showWhitespace = showWhitespace)
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(scene!!.render(System.nanoTime()))

        // The lines of the diff, under the commit strip, where the View button is lit while whitespace shows
        return (0 until bitmap.width * bitmap.height)
            .filter { it / bitmap.width >= 70 && it % bitmap.width >= 300 }
            .map { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
    }

    @Test
    fun `the diff of a commit marks whitespace while the menu says so`() {
        assertTrue(pixelsWithMarks(showWhitespace = true) != pixelsWithMarks(showWhitespace = false))
    }

    @Test
    fun `a commit with no hunks and nothing hidden shows a blank diff, as before`() {
        render(DiffResult.Text(entry, emptyList()), whitespace = DiffWhitespaceMode.SHOW_ALL)

        assertTrue("Only whitespace changed in this file" !in texts())
    }
}
