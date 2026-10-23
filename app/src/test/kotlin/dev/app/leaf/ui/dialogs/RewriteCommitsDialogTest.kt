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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.rewrite.LineRange
import dev.app.leaf.domain.rewrite.RewriteDialogKind
import dev.app.leaf.domain.rewrite.RewriteRequest
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.RewriteFixtures
import dev.app.leaf.ui.RewriteFixtures.add
import dev.app.leaf.ui.RewriteFixtures.cache
import dev.app.leaf.ui.RewriteFixtures.wip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dialog that squashes or rewords a branch's commits (fork-only, step A3 of `docs/fork/squash-and-rebase.md`): its
 * title and subtitle per kind, the commits newest first, the message it starts from with the caret after the subject,
 * "Start from", the pushed commits, and that a message git would leave empty can't be confirmed.
 */
class RewriteCommitsDialogTest {
    private fun squash(vararg commits: dev.app.leaf.domain.models.Commit, pushed: Set<String> = emptySet()) =
        RewriteRequest(RewriteDialogKind.SQUASH, LineRange(RewriteFixtures.line(pushed), commits.toList()))

    private val squashBranch = RewriteRequest(
        RewriteDialogKind.SQUASH_BRANCH,
        LineRange(RewriteFixtures.line(), listOf(add, wip, cache)),
    )

    private val reword = RewriteRequest(RewriteDialogKind.REWORD, LineRange(RewriteFixtures.line(), listOf(wip)))

    @Test
    fun `a squash lists its commits newest first and starts from what they say`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(squash(wip, cache)).use { dialog ->
                val texts = dialog.texts()

                assertTrue("Squash 2 commits into one" in texts, texts.toString())
                assertTrue("On claude/parser-cache." in texts, texts.toString())
                assertTrue(texts.indexOf("Cache the parsed files") < texts.indexOf("wip"), texts.toString())
                assertTrue("c0ffee1" in texts && "b1c2d3e" in texts, texts.toString())
                // "wip" says nothing, so the message is the other commit's.
                assertEquals("Cache the parsed files\n\nKeyed by the file's hash.", dialog.message())
                assertEquals(TextRange(22), dialog.selection())
                assertEquals(true, dialog.isFieldFocused())
                assertTrue(dialog.isClickable("Squash 2 commits"))

                dialog.click("Squash 2 commits")

                assertEquals(listOf("confirm Cache the parsed files\n\nKeyed by the file's hash.", "dismiss"), dialog.calls)
            }
        }

    @Test
    fun `squashing the branch says since when, and Start from replaces the message`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(squashBranch).use { dialog ->
                val texts = dialog.texts()

                assertTrue("Squash claude/parser-cache into one commit" in texts, texts.toString())
                assertTrue("All 3 commits since the branch left main at e3c6632." in texts, texts.toString())
                assertEquals("Add the parser\n\n- Cache the parsed files", dialog.message())
                assertEquals(TextRange(14), dialog.selection())

                dialog.click("All messages")
                assertEquals(
                    "Add the parser\n\nwip\n\nCache the parsed files\n\nKeyed by the file's hash.",
                    dialog.message(),
                )

                dialog.click("Oldest message")
                assertEquals("Add the parser", dialog.message())

                dialog.click("Squash 3 commits")
                assertEquals(listOf("confirm Add the parser", "dismiss"), dialog.calls)
            }
        }

    @Test
    fun `a reword starts from the commit's message, without the list or Start from`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(reword).use { dialog ->
                val texts = dialog.texts()

                assertTrue("Edit the message of b1c2d3e" in texts, texts.toString())
                assertFalse("Start from" in texts, texts.toString())
                assertFalse("Cache the parsed files" in texts, texts.toString())
                assertEquals("wip", dialog.message())
                assertFalse(dialog.isClickable("Save message"))
            }
        }

    @Test
    fun `a reword that keeps the message, as git's clean-up reads it, can't be saved`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(reword).use { dialog ->
                dialog.setMessage("\nwip   \n\n# the same once cleaned\n\n")
                assertFalse(dialog.isClickable("Save message"))
                dialog.pressAccept()
                assertEquals(emptyList<String>(), dialog.calls)

                dialog.setMessage("wip, but kept")
                assertTrue(dialog.isClickable("Save message"))
            }
        }

    @Test
    fun `commits on the upstream are counted, from the range to the tip`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(squash(add, wip, pushed = setOf(add.hash, wip.hash, cache.hash))).use { dialog ->
            assertTrue(
                "3 commits that this rewrites are on origin/claude/parser-cache. Afterwards, push with force to replace them." in
                    dialog.texts(),
                dialog.texts().toString(),
            )
        }

        DialogScene(squash(wip, cache, pushed = setOf(add.hash))).use { dialog ->
            assertFalse(dialog.texts().any { it.contains("push with force") }, dialog.texts().toString())
        }
    }

    @Test
    fun `a message of comments only can't be confirmed, by the button or the shortcut`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(reword).use { dialog ->
                dialog.setMessage("# Only a comment\n\n   ")

                assertFalse(dialog.isClickable("Save message"))
                dialog.pressAccept()
                assertEquals(emptyList<String>(), dialog.calls)

                dialog.setMessage("Parse each file once")

                assertTrue(dialog.isClickable("Save message"))
                dialog.pressAccept()
                assertEquals(listOf("confirm Parse each file once", "dismiss"), dialog.calls)
            }
        }

    @Test
    fun `verbatim keeps a comment line as the message`(): Unit = runBlocking(Dispatchers.Swing) {
        val verbatim = MessageCleanup(dev.app.leaf.domain.commitmessage.CleanupMode.VERBATIM, "#")

        DialogScene(reword, verbatim).use { dialog ->
            dialog.setMessage("# Kept as typed")

            assertTrue(dialog.isClickable("Save message"))
        }
    }

    private class DialogScene(request: RewriteRequest, cleanup: MessageCleanup = MessageCleanup.DEFAULT) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 900, height = 900, density = Density(1f)) {
            AppTheme {
                RewriteCommitsDialog(
                    request = request,
                    cleanup = cleanup,
                    onConfirm = { calls += "confirm $it" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }

        init {
            scene.render()
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun message(): String = field().config[SemanticsProperties.EditableText].text

        fun isFieldFocused() = field().config.getOrNull(SemanticsProperties.Focused)

        fun selection(): TextRange = field().config[SemanticsProperties.TextSelectionRange]

        fun setMessage(text: String) {
            checkNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            scene.render()
        }

        /** Whether the element with the text [text] takes a click: a disabled button doesn't. */
        fun isClickable(text: String): Boolean = clickables(text).any {
            it.config.getOrNull(SemanticsProperties.Disabled) == null
        }

        fun click(text: String) {
            checkNotNull(clickables(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        /** Ctrl+Enter, or Cmd+Enter on macOS, in the message field, which has the focus. */
        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        fun pressAccept() {
            for ((control, meta) in listOf(true to false, false to true)) {
                scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown, isCtrlPressed = control, isMetaPressed = meta))
                scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp, isCtrlPressed = control, isMetaPressed = meta))
            }

            scene.render()
        }

        private fun field(): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.EditableText) != null }

        private fun clickables(text: String) = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
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
}
