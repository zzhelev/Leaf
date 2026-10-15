// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.QuitPrompt
import dev.app.leaf.ui.UnsavedResolverFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun unsaved(path: String, tabName: String?, worktreeName: String? = null) = UnsavedResolverFile(
    path = path,
    tabName = MutableStateFlow(tabName),
    worktreeName = MutableStateFlow(worktreeName),
    save = { _, _ -> },
    show = {},
)

/** The dialog that asks before Leaf quits while conflict resolvers have changes that aren't saved (fork-only). */
class QuitDialogTest {
    @Test
    fun `it names each file and its tab`(): Unit = runBlocking(Dispatchers.Swing) {
        val files = listOf(unsaved("src/main/App.kt", "Leaf", "agent1"), unsaved("README.md", "Leaf-docs"))

        DialogScene(files).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Quit Leaf?" in texts, texts.toString())
            assertTrue(
                "The conflict resolver has changes that aren't saved in 2 files. Saving writes them, with conflict " +
                    "markers for the conflicts still open." in texts,
                texts.toString(),
            )
            assertTrue("src/main/App.kt" in texts, texts.toString())
            assertTrue("Leaf · agent1" in texts, texts.toString())
            assertTrue("README.md" in texts, texts.toString())
            assertTrue("Leaf-docs" in texts, texts.toString())
        }
    }

    @Test
    fun `one file is the file`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(listOf(unsaved("a.txt", "Leaf"))).use { dialog ->
            val texts = dialog.texts()

            assertTrue(
                "The conflict resolver has changes that aren't saved. Saving writes the file, with conflict markers " +
                    "for the conflicts still open." in texts,
                texts.toString(),
            )
        }
    }

    @Test
    fun `a tab whose name isn't known yet shows the file alone`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(listOf(unsaved("a.txt", tabName = null))).use { dialog ->
            assertFalse(dialog.texts().any { it.isBlank() || it.startsWith(" · ") }, dialog.texts().toString())
        }
    }

    @Test
    fun `each button gives its answer`(): Unit = runBlocking(Dispatchers.Swing) {
        val answers = mapOf("Save and quit" to true, "Discard and quit" to false, "Cancel" to null)

        for ((button, answer) in answers) {
            DialogScene(listOf(unsaved("a.txt", "Leaf"))).use { dialog ->
                dialog.click(button)

                assertEquals(listOf(answer), dialog.answers, button)
            }
        }
    }

    @Test
    fun `Enter saves and quits, Esc stays`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(listOf(unsaved("a.txt", "Leaf"))).use { dialog ->
            dialog.press(Key.Enter)

            assertEquals(listOf<Boolean?>(true), dialog.answers)
        }

        DialogScene(listOf(unsaved("a.txt", "Leaf"))).use { dialog ->
            dialog.press(Key.Escape)

            assertEquals(listOf<Boolean?>(null), dialog.answers)
        }
    }

    private class DialogScene(files: List<UnsavedResolverFile>) : AutoCloseable {
        val answers = mutableListOf<Boolean?>()

        private val scene = ImageComposeScene(width = 800, height = 800, density = Density(1f)) {
            AppTheme {
                QuitDialog(QuitPrompt(files), onAnswer = { answers += it })
            }
        }

        init {
            // The second frame runs the dialog's focus request
            scene.render()
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

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
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
