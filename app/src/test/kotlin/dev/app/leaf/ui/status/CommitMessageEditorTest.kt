// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val staged = StatusEntry("src/Session.kt", StatusType.MODIFIED, EntryType.STAGED)

private const val EXPAND = "Write in a larger view"
private const val COLLAPSE = "Back to the changes pane (Esc)"
private const val CENTRE_WIDTH = 600f

/**
 * The commit message in the centre pane (docs/fork/window-chrome.md, section 6): [CommitMessageEditor] where the log is,
 * next to [ChangesPaneCommitField], as `MainContentView` and `StatusPane` show them, with the state that the view model
 * keeps.
 */
class CommitMessageEditorTest {
    @Test
    fun `the button opens the message in the centre, which takes the focus with the caret where it was`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val message = TextFieldValue("Fix the login redirect loop", selection = TextRange(4))

            Panes(ready(message)).use { panes ->
                assertEquals(1, panes.fields().size)
                assertTrue(panes.fields().single().boundsInRoot.left >= CENTRE_WIDTH, "In the changes pane")

                panes.clickDescription(EXPAND)

                assertEquals(listOf(StatusAction.ExpandMessage(true)), panes.actions.filterIsInstance<StatusAction.ExpandMessage>())
                val field = panes.fields().single()
                assertTrue(field.boundsInRoot.right <= CENTRE_WIDTH, "In the centre pane: ${field.boundsInRoot}")
                assertEquals(true, field.config.getOrNull(SemanticsProperties.Focused))
                assertEquals(TextRange(4), field.config.getOrNull(SemanticsProperties.TextSelectionRange))
            }
        }

    @Test
    fun `the centre has the field and the menu, and the changes pane keeps the buttons and says where the message is`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Panes(ready(TextFieldValue("Fix the login redirect loop")), expanded = true).use { panes ->
                assertEquals(1, panes.fields().size, "One field, in the centre")
                assertTrue("Being written in the larger view" in panes.texts(), panes.texts().toString())

                val menus = panes.withDescription("More actions")
                assertEquals(1, menus.size, "The menu is the centre's")
                assertTrue(menus.single().boundsInRoot.right <= CENTRE_WIDTH)

                val commit = panes.withText("Commit")
                assertEquals(1, commit.size, "The buttons are the changes pane's")
                assertTrue(commit.single().boundsInRoot.left >= CENTRE_WIDTH)

                // Both title rows can put it back
                assertEquals(2, panes.withDescription(COLLAPSE).size)
                assertTrue(panes.withDescription(EXPAND).isEmpty())
            }
        }

    @Test
    fun `what is written in the centre is what the changes pane's button commits`(): Unit = runBlocking(Dispatchers.Swing) {
        Panes(ready(TextFieldValue("Fix")), expanded = true).use { panes ->
            panes.setText("Fix the login redirect loop\n\nSafari drops the session cookie.")
            panes.click("Commit")

            assertEquals(
                StatusAction.Commit("Fix the login redirect loop\n\nSafari drops the session cookie."),
                panes.actions.last(),
            )
        }
    }

    @Test
    fun `putting it back, from either title row, gives the changes pane's field the focus`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (side in listOf("centre", "changes pane")) {
                Panes(ready(TextFieldValue("Fix the login redirect loop")), expanded = true).use { panes ->
                    val button = panes.withDescription(COLLAPSE).single { node ->
                        (node.boundsInRoot.right <= CENTRE_WIDTH) == (side == "centre")
                    }
                    panes.clickNode(button)

                    assertEquals(StatusAction.ExpandMessage(false), panes.actions.filterIsInstance<StatusAction.ExpandMessage>().last(), side)
                    val field = panes.fields().single()
                    assertTrue(field.boundsInRoot.left >= CENTRE_WIDTH, side)
                    assertEquals(true, field.config.getOrNull(SemanticsProperties.Focused), side)
                    assertTrue("Being written in the larger view" !in panes.texts(), side)
                }
            }
        }

    @Test
    fun `a field that can't be edited has no button to enlarge it`(): Unit = runBlocking(Dispatchers.Swing) {
        val stop = ready(TextFieldValue("")).copy(repositoryState = RepositoryState.REBASING_INTERACTIVE)

        Panes(stop).use { panes ->
            assertTrue("· read-only" in panes.texts(), panes.texts().toString())
            assertTrue(panes.withDescription(EXPAND).isEmpty())
        }

        Panes(ready(TextFieldValue(""))).use { panes ->
            assertEquals(1, panes.withDescription(EXPAND).size)
        }
    }

    private fun ready(message: TextFieldValue) = StatusState(
        isLoading = false,
        staged = listOf(staged),
        commitMessage = message,
        previousCommitMessage = "Previous",
        repositoryState = RepositoryState.SAFE,
    )

    /** The centre pane and the changes pane's commit field, with the view model's state for the message. */
    private class Panes(initial: StatusState, expanded: Boolean = false) : AutoCloseable {
        private var state by mutableStateOf(initial)
        private var isExpanded by mutableStateOf(expanded)
        val actions = mutableListOf<StatusAction>()

        private fun onAction(action: StatusAction) {
            actions += action

            when (action) {
                is StatusAction.UpdateCommitMessage -> state = state.copy(commitMessage = action.message)
                is StatusAction.ExpandMessage -> isExpanded = action.expanded
                else -> Unit
            }
        }

        private val scene = ImageComposeScene(width = 930, height = 300, density = Density(1f)) {
            AppTheme {
                Row {
                    Box(Modifier.width(CENTRE_WIDTH.dp).fillMaxHeight()) {
                        if (isExpanded) {
                            CommitMessageEditor(state, ::onAction, onConfirmAction = { _, onConfirm -> onConfirm() })
                        }
                    }

                    Box(Modifier.width(330.dp)) {
                        ChangesPaneCommitField(
                            statusState = state,
                            onAction = ::onAction,
                            onConfirmAction = { _, onConfirm -> onConfirm() },
                            isMessageExpanded = isExpanded,
                            modifier = Modifier.height(260.dp),
                            branchName = "main",
                        )
                    }
                }
            }
        }

        init {
            settle()
        }

        private fun settle() = repeat(4) { scene.render() }

        fun fields() = nodes().filter { it.config.contains(SemanticsProperties.EditableText) }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun withText(text: String) = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        fun withDescription(description: String) = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

        fun click(text: String) = clickNode(withText(text).single())

        fun clickDescription(description: String) = clickNode(withDescription(description).single())

        fun clickNode(node: SemanticsNode) {
            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        /** Replaces the text of the field that has the focus, as typing would. */
        fun setText(text: String) {
            val field = fields().single { it.config.getOrNull(SemanticsProperties.Focused) == true }
            checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            settle()
        }

        override fun close() = scene.close()

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }
    }
}
