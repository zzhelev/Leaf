// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.height
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val untrackedFile = StatusEntry("build.log", StatusType.ADDED)
private val changedFile = StatusEntry("README.md", StatusType.MODIFIED)
private val conflictedFile = StatusEntry("notes.txt", StatusType.CONFLICTING)
private val stagedFile = StatusEntry("src/Main.kt", StatusType.MODIFIED, EntryType.STAGED)

private fun status(
    repositoryState: RepositoryState,
    staged: List<StatusEntry> = emptyList(),
    unstaged: List<StatusEntry> = emptyList(),
    message: String = "",
    isLoading: Boolean = false,
) = StatusState(
    isLoading = isLoading,
    staged = staged,
    unstaged = unstaged,
    commitMessage = TextFieldValue(message),
    previousCommitMessage = "Previous",
    repositoryState = repositoryState,
)

/**
 * The commit field's buttons and the line that says why the primary one is disabled (upstream issues 120 and 124),
 * rendered from a [StatusState] as `StatusPane` renders them. A button that is disabled does nothing when clicked, and
 * the keyboard shortcut for committing does what the primary button does.
 */
class CommitFieldTest {
    // --- Merge

    @Test
    fun `Merge works with an untracked file and a changed file around, and says nothing`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = status(RepositoryState.MERGING, unstaged = listOf(untrackedFile, changedFile), message = "Merge branch 'agent1'")

            Field(state).use { field ->
                assertEquals(emptyList<String>(), field.hints())
                assertTrue("Merge" in field.texts() && "Abort" in field.texts())

                field.click("Merge")

                assertEquals(1, field.commits)
            }
        }

    @Test
    fun `Merge works with nothing staged, as when every conflict was taken from our side`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Field(status(RepositoryState.MERGING_RESOLVED, unstaged = listOf(untrackedFile), message = "Merge branch 'agent1'")).use { field ->
                field.click("Merge")

                assertEquals(1, field.commits)
            }
        }

    @Test
    fun `Merge is disabled while files have conflicts, with the reason next to it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = status(RepositoryState.MERGING, unstaged = listOf(conflictedFile, untrackedFile), message = "Merge branch 'agent1'")

            Field(state).use { field ->
                assertEquals(listOf("Resolve the conflicts in 1 file first."), field.hints())

                field.click("Merge")
                field.pressCommitShortcut()

                assertEquals(0, field.commits)
            }
        }

    // --- Cherry-pick and revert

    @Test
    fun `a cherry-pick or a revert commits with files around that aren't staged`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (repositoryState in listOf(RepositoryState.CHERRY_PICKING_RESOLVED, RepositoryState.REVERTING_RESOLVED)) {
                val label = if (repositoryState.isReverting) "Continue" else "Commit"
                val state = status(
                    repositoryState,
                    staged = listOf(stagedFile),
                    unstaged = listOf(untrackedFile, changedFile),
                    message = "Pick it",
                )

                Field(state).use { field ->
                    assertEquals(emptyList<String>(), field.hints(), "$repositoryState")

                    field.click(label)

                    assertEquals(1, field.commits, "$repositoryState")
                }
            }
        }

    @Test
    fun `a cherry-pick or a revert says what is missing`(): Unit = runBlocking(Dispatchers.Swing) {
        for (repositoryState in listOf(RepositoryState.CHERRY_PICKING, RepositoryState.REVERTING)) {
            val label = if (repositoryState.isReverting) "Continue" else "Commit"

            Field(status(repositoryState, staged = listOf(stagedFile), message = " ")).use { field ->
                assertEquals(listOf("Write a commit message."), field.hints(), "$repositoryState")
                field.click(label)
                assertEquals(0, field.commits)
            }

            Field(status(repositoryState, unstaged = listOf(changedFile), message = "Pick it")).use { field ->
                assertEquals(listOf("Stage some changes to commit."), field.hints(), "$repositoryState")
                field.click(label)
                assertEquals(0, field.commits)
            }

            Field(status(repositoryState, unstaged = listOf(conflictedFile), message = "Pick it")).use { field ->
                assertEquals(listOf("Resolve the conflicts in 1 file first."), field.hints(), "$repositoryState")
            }
        }
    }

    @Test
    fun `a revert waits for the status to load, without saying anything`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = status(RepositoryState.REVERTING, staged = listOf(stagedFile), message = "Revert", isLoading = true)

        Field(state).use { field ->
            assertEquals(emptyList<String>(), field.hints())

            field.click("Continue")
            field.pressCommitShortcut()

            assertEquals(0, field.commits)
        }
    }

    // --- Rebase

    @Test
    fun `Continue works with something staged and an untracked file around`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = status(RepositoryState.REBASING_MERGE, staged = listOf(stagedFile), unstaged = listOf(untrackedFile))

        Field(state).use { field ->
            assertEquals(emptyList<String>(), field.hints())

            field.click("Continue")

            assertEquals(1, field.continues)
        }
    }

    @Test
    fun `Continue is disabled by changes to tracked files that aren't staged, with the reason`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = status(
                RepositoryState.REBASING_MERGE,
                staged = listOf(stagedFile),
                unstaged = listOf(changedFile, untrackedFile),
            )

            Field(state).use { field ->
                assertEquals(listOf("Stage or discard the changes to 1 tracked file to continue."), field.hints())

                field.click("Continue")

                assertEquals(0, field.continues)
                // Abort still works
                field.click("Abort")
                assertEquals(1, field.aborts)
            }
        }

    @Test
    fun `Continue is disabled by conflicts, and stays there rather than turning into Skip`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Field(status(RepositoryState.REBASING_INTERACTIVE, unstaged = listOf(conflictedFile))).use { field ->
                assertEquals(listOf("Resolve the conflicts in 1 file first."), field.hints())
                assertTrue("Continue" in field.texts())
                assertFalse("Skip" in field.texts())
            }
        }

    @Test
    fun `with only an untracked file left, the rebase offers Skip, which works`(): Unit = runBlocking(Dispatchers.Swing) {
        Field(status(RepositoryState.REBASING_MERGE, unstaged = listOf(untrackedFile))).use { field ->
            assertEquals(emptyList<String>(), field.hints())
            assertTrue("Skip" in field.texts())
            assertFalse("Continue" in field.texts())

            field.click("Skip")

            assertEquals(1, field.skips)
            assertEquals(0, field.continues)
        }
    }

    @Test
    fun `a rebase stopped to amend offers Continue with an amended message, and the unstaged rules apply`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val stop = RebaseInteractiveState.ProcessingCommits(commitToAmendId = "abc1234")
            val state = status(RepositoryState.REBASING_INTERACTIVE, message = "Reworded").copy(
                rebaseInteractiveState = stop,
                isAmendRebaseInteractive = true,
            )

            Field(state).use { field ->
                assertEquals(emptyList<String>(), field.hints())
                assertTrue("Continue" in field.texts())

                field.click("Continue")

                assertEquals(1, field.continues)
            }

            Field(state.copy(unstaged = listOf(changedFile))).use { field ->
                assertEquals(listOf("Stage or discard the changes to 1 tracked file to continue."), field.hints())

                field.click("Continue")

                assertEquals(0, field.continues)
            }
        }

    // --- Commit, outside an operation

    @Test
    fun `Commit says nothing while the field rests, and what is missing once the user has started`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Field(status(RepositoryState.SAFE, unstaged = listOf(changedFile))).use { field ->
                assertEquals(emptyList<String>(), field.hints())
            }

            Field(status(RepositoryState.SAFE, unstaged = listOf(changedFile), message = "Fix")).use { field ->
                assertEquals(listOf("Stage some changes to commit."), field.hints())

                field.click("Commit")

                assertEquals(0, field.commits)
            }

            Field(status(RepositoryState.SAFE, staged = listOf(stagedFile))).use { field ->
                assertEquals(listOf("Write a commit message."), field.hints())
            }

            Field(status(RepositoryState.SAFE, staged = listOf(stagedFile), message = "Fix")).use { field ->
                assertEquals(emptyList<String>(), field.hints())

                field.click("Commit")

                assertEquals(1, field.commits)
            }
        }

    // --- The shortcut

    @Test
    fun `the commit shortcut does what the primary button does, in a merge too`(): Unit = runBlocking(Dispatchers.Swing) {
        // Nothing staged: Merge works, so the shortcut does (it needed staged files before)
        Field(status(RepositoryState.MERGING, unstaged = listOf(untrackedFile), message = "Merge branch 'agent1'")).use { field ->
            field.pressCommitShortcut()

            assertTrue(field.commits >= 1)
        }

        // A file staged, and another changed: Merge is disabled by nothing now, but a conflict still stops both
        Field(status(RepositoryState.MERGING, staged = listOf(stagedFile), unstaged = listOf(conflictedFile), message = "Merge branch 'agent1'"))
            .use { field ->
                field.pressCommitShortcut()

                assertEquals(0, field.commits)
            }

        Field(status(RepositoryState.SAFE, staged = listOf(stagedFile), message = "Fix")).use { field ->
            field.pressCommitShortcut()

            assertTrue(field.commits >= 1)
        }

        Field(status(RepositoryState.SAFE, unstaged = listOf(changedFile), message = "Fix")).use { field ->
            field.pressCommitShortcut()

            assertEquals(0, field.commits)
        }
    }

    // --- The title row (fork-only, upstream issue 344)

    @Test
    fun `the title row says Commit message, and has no Amend checkbox under the field`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Field(status(RepositoryState.SAFE, staged = listOf(stagedFile), message = "Fix")).use { field ->
                assertTrue("Commit message" in field.texts(), field.texts().toString())
                assertFalse("Amend previous commit" in field.texts())
                assertFalse(field.texts().any { it.startsWith("·") })
                assertTrue("More actions" in field.descriptions())
            }
        }

    @Test
    fun `the title says Merge message while merging, and the other operations and a read-only rebase have no menu`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Field(status(RepositoryState.MERGING, message = "Merge branch 'agent1'")).use { field ->
                assertTrue("Merge message" in field.texts() && "Commit message" !in field.texts())
                assertFalse("More actions" in field.descriptions())
            }

            for (state in listOf(RepositoryState.CHERRY_PICKING, RepositoryState.REVERTING)) {
                Field(status(state, staged = listOf(stagedFile), message = "Fix")).use { field ->
                    assertTrue("Commit message" in field.texts(), "$state")
                    assertFalse("More actions" in field.descriptions(), "$state")
                }
            }

            // The rebase's other stops can't amend: the field shows the last message and the row says it can't be edited
            Field(status(RepositoryState.REBASING_INTERACTIVE, staged = listOf(stagedFile))).use { field ->
                assertTrue("Commit message" in field.texts())
                assertTrue("· read-only" in field.texts(), field.texts().toString())
                assertFalse("More actions" in field.descriptions())
            }
        }

    @Test
    fun `Amend last commit is in the menu, turns the row and the button over, and turns off again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = status(RepositoryState.SAFE, staged = listOf(stagedFile), message = "Fix")

            Field(state).use { field ->
                assertFalse("Amend last commit" in field.texts(), "The menu starts closed")
                assertTrue("Commit" in field.texts() && "Amend" !in field.texts())

                field.clickDescription("More actions")
                field.click("Amend last commit")

                assertEquals(listOf(true), field.amendToggles)
                assertEquals(emptyList<Boolean>(), field.rebaseAmendToggles)
            }

            Field(state.copy(isAmend = true)).use { field ->
                assertTrue("· amending last commit" in field.texts(), field.texts().toString())
                assertTrue("Amend" in field.texts() && "Commit" !in field.texts())

                field.clickDescription("More actions")
                field.click("Amend last commit")

                assertEquals(listOf(false), field.amendToggles)
            }
        }

    @Test
    fun `at a rebase stop that can be amended the menu is there, and it is the rebase's amend switch`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val stop = RebaseInteractiveState.ProcessingCommits(commitToAmendId = "abc1234")
            val state = status(RepositoryState.REBASING_INTERACTIVE, message = "Reworded").copy(rebaseInteractiveState = stop)

            Field(state).use { field ->
                assertFalse(field.texts().any { it.startsWith("·") })

                field.clickDescription("More actions")
                field.click("Amend last commit")

                assertEquals(listOf(true), field.rebaseAmendToggles)
                assertEquals(emptyList<Boolean>(), field.amendToggles)
            }

            Field(state.copy(isAmendRebaseInteractive = true)).use { field ->
                assertTrue("· amending last commit" in field.texts())
                // Continue amends, so it is still Continue
                assertTrue("Continue" in field.texts() && "Amend" !in field.texts())

                field.clickDescription("More actions")
                field.click("Amend last commit")

                assertEquals(listOf(false), field.rebaseAmendToggles)
            }
        }

    @Test
    fun `the title row, the field and the button fill the section, with the other buttons' gap above the button`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Field(status(RepositoryState.SAFE, staged = listOf(stagedFile), message = "Fix")).use { field ->
                val message = field.fieldBounds()
                val commit = field.bounds("Commit")

                // The title row is 34 dp, and the Amend row that was under the field is gone
                assertEquals(34f, message.top, 0.5f, "The field starts under the title row")
                assertEquals(8f, commit.top - message.bottom, 0.5f, "The same 8 dp as in a merge")
                assertEquals(220f, commit.bottom, 0.5f, "The button ends the section")
            }
        }

    // --- Where the hint is

    @Test
    fun `the hint is between the message field and the buttons`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = status(RepositoryState.MERGING, unstaged = listOf(conflictedFile), message = "Merge branch 'agent1'")

        Field(state).use { field ->
            val hint = field.bounds("Resolve the conflicts in 1 file first.")
            val merge = field.bounds("Merge")
            val abort = field.bounds("Abort")

            assertTrue(hint.bottom <= merge.top, "hint ends at ${hint.bottom}, Merge starts at ${merge.top}")
            assertTrue(hint.bottom <= abort.top)
            assertTrue(hint.left >= 0f && hint.right <= 400f, "hint spans ${hint.left} to ${hint.right}")
        }
    }

    /**
     * The commit field for [state], with what `StatusPane` passes it. Counts what its buttons and its shortcut do.
     */
    private class Field(state: StatusState) : AutoCloseable {
        var commits = 0
        var continues = 0
        var skips = 0
        var aborts = 0
        val amendToggles = mutableListOf<Boolean>()
        val rebaseAmendToggles = mutableListOf<Boolean>()

        private val scene = ImageComposeScene(width = 400, height = 220, density = Density(1f)) {
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
                    onResetRepoState = { aborts++ },
                    onAbortRebase = { aborts++ },
                    onContinueRebase = { continues++ },
                    onSkipRebase = { skips++ },
                    onAmendChecked = { amendToggles += it },
                    onAmendRebaseInteractiveChecked = { rebaseAmendToggles += it },
                    modifier = Modifier.height(220.dp),
                )
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** The lines that say why a button is disabled: the texts that aren't a label or a button's. */
        fun hints(): List<String> = texts().distinct().filter { text ->
            text.endsWith(".") && text != "Previous"
        }

        fun descriptions(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

        /** Clicks the clickable element described as [description]. */
        fun clickDescription(description: String) {
            val node = nodes().first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        /** The message field's bounds. */
        fun fieldBounds() = nodes().first { it.config.contains(SemanticsProperties.EditableText) }.boundsInRoot

        fun bounds(text: String) = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }.boundsInRoot

        /** Clicks the clickable element whose text is [text]. */
        fun click(text: String) {
            val node = nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        /** Focuses the message field with a click (under the title row), then presses the shortcut: Ctrl+Enter, or Cmd+Enter on macOS. */
        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        fun pressCommitShortcut() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 70f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 70f))
            scene.render()

            for ((control, meta) in listOf(true to false, false to true)) {
                scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown, isCtrlPressed = control, isMetaPressed = meta))
                scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp, isCtrlPressed = control, isMetaPressed = meta))
            }

            scene.render()
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
