// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.FilesChangedHeader
import dev.app.leaf.ui.components.SectionFold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SUBJECT = "Fold the status pane's sections"

/**
 * The headers of the status pane's sections that fold (fork-only): Staged's and Unstaged's with a chevron and a count,
 * no sort or search while folded; the commit message's row with the subject while folded, and the buttons under it but
 * no field.
 */
class SectionFoldHeadersTest {
    @Test
    fun `a list's header has a chevron and a count, folds with a click, and says whether it's open`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Header(isFolded = false).use { header ->
                assertTrue("Staged" in header.texts() && "3" in header.texts())
                assertEquals("Open", header.foldNode().config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue("Sort and group" in header.descriptions())
                assertEquals(4, header.clickables().size, "The fold, the sort menu, the search and Unstage all")

                header.clickFold()

                assertEquals(listOf(true), header.toggles)
                assertEquals("Folded", header.foldNode().config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue("Staged" in header.texts() && "3" in header.texts())
                assertFalse("Sort and group" in header.descriptions())
                assertEquals(2, header.clickables().size, "The fold and Unstage all")
            }
        }

    @Test
    fun `a header that doesn't fold is as before, with no count and nothing to click on it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Header(isFolded = null).use { header ->
                assertTrue("Staged" in header.texts())
                assertFalse("3" in header.texts())
                assertNull(header.nodes().firstOrNull { it.config.contains(SemanticsProperties.StateDescription) })
                assertEquals(3, header.clickables().size, "The sort menu, the search and Unstage all")
            }
        }

    @Test
    fun `the folded commit message shows its subject after the title, and the buttons, with no field or menu`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = StatusState(
                staged = listOf(StatusEntry("a.kt", StatusType.MODIFIED, EntryType.STAGED)),
                commitMessage = TextFieldValue("# a note\n\n$SUBJECT\n\nThe body"),
                repositoryState = RepositoryState.SAFE,
            )

            CommitSection(state, CommitFieldPart.FOLDED).use { field ->
                assertTrue("Commit message" in field.texts())
                assertTrue("· $SUBJECT" in field.texts(), "${field.texts()}")
                assertFalse(field.nodes().any { it.config.contains(SemanticsProperties.EditableText) })
                assertFalse("More actions" in field.descriptions())
                assertFalse("Write in a larger view" in field.descriptions())
                assertFalse("Back to the changes pane (Esc)" in field.descriptions())
                assertEquals("Folded", field.foldNode().config.getOrNull(SemanticsProperties.StateDescription))

                field.click("Commit")

                assertEquals(1, field.commits)

                field.clickFold()

                assertEquals(1, field.toggles)
            }
        }

    @Test
    fun `the folded commit message says what stops its button`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = StatusState(commitMessage = TextFieldValue(SUBJECT), repositoryState = RepositoryState.SAFE)

        CommitSection(state, CommitFieldPart.FOLDED).use { field ->
            assertTrue("Stage some changes to commit." in field.texts(), "${field.texts()}")
        }
    }

    @Test
    fun `an open commit message has the chevron, the field and the menu, and no subject in its row`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = StatusState(commitMessage = TextFieldValue(SUBJECT), repositoryState = RepositoryState.SAFE)

            CommitSection(state, CommitFieldPart.WHOLE, isFolded = false).use { field ->
                assertEquals("Open", field.foldNode().config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue(field.nodes().any { it.config.contains(SemanticsProperties.EditableText) })
                assertTrue("More actions" in field.descriptions())
                assertFalse("· $SUBJECT" in field.texts())
            }
        }

    @Test
    fun `the changes pane shows the folded part while folded, also while the message is in the centre pane`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = StatusState(commitMessage = TextFieldValue(SUBJECT), repositoryState = RepositoryState.SAFE)

            for (isExpanded in listOf(false, true)) {
                ChangesPaneSection(state, isFolded = true, isExpanded = isExpanded).use { field ->
                    assertTrue("· $SUBJECT" in field.texts(), "expanded $isExpanded: ${field.texts()}")
                    assertFalse(field.nodes().any { it.config.contains(SemanticsProperties.EditableText) })
                    assertFalse("Being written in the larger view" in field.texts())
                }
            }

            ChangesPaneSection(state, isFolded = false, isExpanded = false).use { field ->
                assertTrue(field.nodes().any { it.config.contains(SemanticsProperties.EditableText) })
                assertFalse("· $SUBJECT" in field.texts())
            }
        }

    @Test
    fun `the subject is the first line that git keeps`() {
        val cleanup = MessageCleanup.DEFAULT

        assertEquals(SUBJECT, foldedSubject("\n# Conflicts:\n#\ta.kt\n  \n$SUBJECT  \n\nBody", cleanup))
        assertEquals(null, foldedSubject("", cleanup))
        assertEquals(null, foldedSubject("# only a comment", cleanup))
    }

    private class Header(isFolded: Boolean?) : Rendered() {
        val toggles = mutableListOf<Boolean>()
        private var folded by mutableStateOf(isFolded)

        override val content: @Composable () -> Unit = {
            AppTheme {
                FilesChangedHeader(
                    title = "Staged",
                    showSearch = false,
                    showActionForSelected = false,
                    actionInfo = getActionInfo(EntryType.STAGED),
                    onAllAction = {},
                    sortAction = {
                        dev.app.leaf.ui.components.sort.FilesSortMenuButton(
                            viewState = dev.app.leaf.domain.sorting.FilesViewState(),
                            onViewStateChange = {},
                        )
                    },
                    onSearchFilterToggled = {},
                    onSearchFocused = {},
                    searchFilter = TextFieldValue(""),
                    onSearchFilterChanged = {},
                    fold = folded?.let { isFolded ->
                        SectionFold(isFolded) {
                            toggles += !isFolded
                            folded = !isFolded
                        }
                    },
                    count = 3.takeIf { folded != null },
                )
            }
        }

        init {
            start()
        }
    }

    private class CommitSection(state: StatusState, part: CommitFieldPart, isFolded: Boolean = true) : Rendered() {
        var commits = 0
        var toggles = 0

        override val content: @Composable () -> Unit = {
            AppTheme {
                CommitField(
                    canCommit = state.canCommit,
                    isAmend = state.isAmend,
                    canAmend = state.canAmend,
                    doCommit = { commits++ },
                    commitMessage = state.commitMessage,
                    previousCommitMessage = state.previousCommitMessage,
                    repositoryState = state.repositoryState,
                    isAmenableRebaseInteractive = state.isAmenableRebaseInteractive,
                    showsContinue = state.showsContinue(),
                    finishBlocker = state.finishBlocker(),
                    isAmendRebaseInteractive = state.isAmendRebaseInteractive,
                    finishHint = state.finishHint(),
                    isLoading = state.isLoading,
                    setCommitMessage = {},
                    onResetRepoState = {},
                    onAbortRebase = {},
                    onContinueRebase = {},
                    onSkipRebase = {},
                    onAmendChecked = {},
                    onAmendRebaseInteractiveChecked = {},
                    modifier = if (part == CommitFieldPart.FOLDED) Modifier else Modifier.width(330.dp),
                    part = part,
                    onToggleExpanded = {},
                    fold = SectionFold(isFolded) { toggles++ },
                )
            }
        }

        init {
            start()
        }
    }

    private class ChangesPaneSection(state: StatusState, isFolded: Boolean, isExpanded: Boolean) : Rendered() {
        override val content: @Composable () -> Unit = {
            AppTheme {
                ChangesPaneCommitField(
                    statusState = state,
                    onAction = {},
                    onConfirmAction = { _, onConfirm -> onConfirm() },
                    isMessageExpanded = isExpanded,
                    modifier = if (isFolded) Modifier else Modifier.height(260.dp),
                    fold = SectionFold(isFolded) {},
                )
            }
        }

        init {
            start()
        }
    }

    private abstract class Rendered : AutoCloseable {
        abstract val content: @Composable () -> Unit
        private var scene: ImageComposeScene? = null

        fun start() {
            scene = ImageComposeScene(width = 330, height = 300, density = Density(1f), content = content)
            render()
        }

        private fun render() = repeat(4) { scene!!.render() }

        fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun descriptions(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

        fun clickables() = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }

        fun foldNode() = nodes().single { it.config.contains(SemanticsProperties.StateDescription) }

        fun clickFold() {
            checkNotNull(foldNode().config[SemanticsActions.OnClick].action).invoke()
            render()
        }

        fun click(text: String) {
            val node = nodes().first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            render()
        }

        override fun close() {
            scene?.close()
        }
    }
}
