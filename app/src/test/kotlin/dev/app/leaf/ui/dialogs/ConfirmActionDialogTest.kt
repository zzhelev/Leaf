// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val STOP_ASKING = "Don't ask again for hunks and lines"
private const val FILE = "src/app/Foo.kt"

private val addedLine = Line("    println(\"debug\")\n", 4, 5, LineType.ADDED)
private val removedLine = Line("    val old = 1\n", 6, 6, LineType.REMOVED)
private val contextLine = Line("fun foo() {\n", 3, 3, LineType.CONTEXT)

/** The dialog only reads the header and the lines, not the texts that the patch comes from. */
private fun hunk(header: String, vararg lines: Line) =
    Hunk(header, lines.toList(), RawText.EMPTY_TEXT, RawText.EMPTY_TEXT)

/** The confirmations of discards, and the "Don't ask again" of hunks and lines (fork-only). */
class ConfirmActionDialogTest {
    @Test
    fun `discarding with the box checked stops asking, then discards`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = true).use { dialog ->
            dialog.click(STOP_ASKING)
            dialog.click("Discard")

            assertEquals(listOf("stop asking", "confirm"), dialog.calls)
        }
    }

    @Test
    fun `discarding with the box unchecked keeps asking`(): Unit = runBlocking(Dispatchers.Swing) {
        val discarded = hunk("@@ -4,3 +4,4 @@", contextLine, addedLine)

        DialogScene(ConfirmableAction.DiscardHunk(FILE, discarded), canStopAsking = true).use { dialog ->
            assertTrue(STOP_ASKING in dialog.texts())

            dialog.click("Discard")

            assertEquals(listOf("confirm"), dialog.calls)
        }
    }

    @Test
    fun `checking the box twice unchecks it`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = true).use { dialog ->
            dialog.click(STOP_ASKING)
            dialog.click(STOP_ASKING)
            dialog.click("Discard")

            assertEquals(listOf("confirm"), dialog.calls)
        }
    }

    @Test
    fun `cancelling with the box checked keeps asking`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = true).use { dialog ->
            dialog.click(STOP_ASKING)
            dialog.click("Cancel")

            assertEquals(listOf("dismiss"), dialog.calls)
        }
    }

    @Test
    fun `without a way to stop asking there is no box`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = false).use { dialog ->
            assertFalse(STOP_ASKING in dialog.texts())
        }
    }

    @Test
    fun `discarding files always asks`(): Unit = runBlocking(Dispatchers.Swing) {
        val actions = listOf(
            ConfirmableAction.DiscardFile(StatusEntry(FILE, StatusType.MODIFIED, EntryType.UNSTAGED)),
            ConfirmableAction.DiscardFiles(3, EntryType.STAGED),
        )

        for (action in actions) {
            DialogScene(action, canStopAsking = true).use { dialog ->
                assertFalse(dialog.texts().any { it.startsWith("Don't ask again") }, action.toString())
            }
        }
    }

    @Test
    fun `an added line can't be restored, a removed line comes back`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard this added line in \"$FILE\"?" in texts, texts.toString())
            assertTrue("+ println(\"debug\")" in texts, texts.toString())
            assertTrue("It isn't staged, so it can't be restored." in texts, texts.toString())
        }

        DialogScene(ConfirmableAction.DiscardLine(FILE, removedLine)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Put this removed line back into \"$FILE\"?" in texts, texts.toString())
            assertTrue("- val old = 1" in texts, texts.toString())
            assertFalse(texts.any { it.contains("can't be restored") }, texts.toString())
        }
    }

    @Test
    fun `a hunk warns about its added lines only`(): Unit = runBlocking(Dispatchers.Swing) {
        val secondAdded = addedLine.copy(newLineNumber = 6)
        val withAdded = hunk("@@ -4,3 +4,5 @@ fun foo()\n", contextLine, addedLine, secondAdded, removedLine)

        DialogScene(ConfirmableAction.DiscardHunk(FILE, withAdded)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("@@ -4,3 +4,5 @@ fun foo()" in texts, texts.toString())
            assertTrue("Its 2 added lines aren't staged, so they can't be restored." in texts, texts.toString())
        }

        val removalsOnly = hunk("@@ -4,3 +4,2 @@", contextLine, removedLine)

        DialogScene(ConfirmableAction.DiscardHunk(FILE, removalsOnly)).use { dialog ->
            assertFalse(dialog.texts().any { it.contains("can't be restored") }, dialog.texts().toString())
        }
    }

    @Test
    fun `discarding a staged file warns that its unstaged changes go too`(): Unit = runBlocking(Dispatchers.Swing) {
        val staged = StatusEntry(FILE, StatusType.MODIFIED, EntryType.STAGED)

        DialogScene(ConfirmableAction.DiscardFile(staged)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the changes to \"$FILE\"?" in texts, texts.toString())
            assertTrue(
                "Both its staged and unstaged changes are discarded. They can't be restored." in texts,
                texts.toString(),
            )
        }

        DialogScene(ConfirmableAction.DiscardFiles(3, EntryType.UNSTAGED)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the unstaged changes to 3 files?" in texts, texts.toString())
            assertTrue("They can't be restored." in texts, texts.toString())
        }
    }

    @Test
    fun `discarding the deletion of a file loses nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        val deleted = StatusEntry(FILE, StatusType.REMOVED, EntryType.UNSTAGED)

        DialogScene(ConfirmableAction.DiscardFile(deleted)).use { dialog ->
            assertTrue("Discard the unstaged changes to \"$FILE\"?" in dialog.texts())
            assertFalse(dialog.texts().any { it.contains("can't be restored") }, dialog.texts().toString())
        }
    }

    /** [ConfirmActionDialog] for [action], rendered offscreen. [calls] records what the dialog called. */
    private class DialogScene(action: ConfirmableAction, canStopAsking: Boolean = false) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 800, height = 800, density = Density(1f)) {
            AppTheme {
                ConfirmActionDialog(
                    action = action,
                    onConfirm = { calls += "confirm" },
                    onDismiss = { calls += "dismiss" },
                    onStopAsking = if (canStopAsking) ({ calls += "stop asking" }) else null,
                )
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** Clicks the clickable element whose text is [text]. */
        fun click(text: String) {
            val node = nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        /** Every node of the merged tree, where a button's text is part of the button. */
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
}
