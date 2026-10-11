// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.dialogs.ConfirmableAction.AbortOperation.Operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private val conflicted = StatusEntry("notes.txt", StatusType.CONFLICTING)
private val otherConflict = StatusEntry("src/Main.kt", StatusType.CONFLICTING)
private val modified = StatusEntry("README.md", StatusType.MODIFIED)

/**
 * The banner of a merge, rebase, cherry-pick or revert in progress: when it shows, what it says, and that its Abort
 * asks first, as the status pane's does.
 */
class OperationInProgressBannerTest {
    @Test
    fun `a repository with nothing in progress has no banner`() {
        assertNull(operationInProgress(StatusState(repositoryState = RepositoryState.SAFE, unstaged = listOf(modified))))
    }

    @Test
    fun `each operation in progress has one, with the files that still have conflicts`() {
        val conflicts = listOf(conflicted, otherConflict, modified)

        assertEquals(
            OperationInProgress(Operation.MERGE, 2),
            operationInProgress(StatusState(repositoryState = RepositoryState.MERGING, unstaged = conflicts)),
        )
        assertEquals(
            OperationInProgress(Operation.REBASE, 1),
            operationInProgress(
                StatusState(
                    repositoryState = RepositoryState.REBASING_MERGE,
                    // A file that is in both lists counts once
                    staged = listOf(conflicted.copy(entryType = EntryType.STAGED)),
                    unstaged = listOf(conflicted),
                )
            ),
        )
        assertEquals(
            OperationInProgress(Operation.CHERRY_PICK, 1),
            operationInProgress(StatusState(repositoryState = RepositoryState.CHERRY_PICKING, unstaged = listOf(conflicted))),
        )
        assertEquals(
            OperationInProgress(Operation.REVERT, 0),
            operationInProgress(StatusState(repositoryState = RepositoryState.REVERTING_RESOLVED)),
        )
        assertEquals(
            OperationInProgress(Operation.MERGE, 0),
            operationInProgress(StatusState(repositoryState = RepositoryState.MERGING_RESOLVED, unstaged = listOf(modified))),
        )
    }

    @Test
    fun `the interactive rebase's list, which has its own buttons, gets no banner`() {
        val state = StatusState(
            repositoryState = RepositoryState.REBASING_INTERACTIVE,
            rebaseInteractiveState = RebaseInteractiveState.AwaitingInteraction(emptyList()),
        )

        assertNull(operationInProgress(state))
    }

    @Test
    fun `abort asks first, then aborts the operation and clears the commit message`() {
        assertEquals(
            Aborted(
                ConfirmableAction.AbortOperation(Operation.MERGE, 2),
                listOf(StatusAction.ResetRepositoryState, StatusAction.UpdateCommitMessage(TextFieldValue(""))),
            ),
            abort(RepositoryState.MERGING),
        )
        assertEquals(
            Aborted(
                ConfirmableAction.AbortOperation(Operation.REBASE, 2),
                listOf(StatusAction.AbortRebase, StatusAction.UpdateCommitMessage(TextFieldValue(""))),
            ),
            abort(RepositoryState.REBASING_MERGE),
        )
        assertEquals(ConfirmableAction.AbortOperation(Operation.CHERRY_PICK, 2), abort(RepositoryState.CHERRY_PICKING).asked)
        assertEquals(ConfirmableAction.AbortOperation(Operation.REVERT, 2), abort(RepositoryState.REVERTING).asked)
    }

    @Test
    fun `the banner says what stopped and what to do, and its button aborts`(): Unit = runBlocking(Dispatchers.Swing) {
        assertEquals(
            listOf("Merge stopped at conflicts in 1 file. Resolve them, then commit.", "Abort merge"),
            render(OperationInProgress(Operation.MERGE, 1)).texts,
        )
        assertEquals(
            listOf("Rebase stopped at conflicts in 2 files. Resolve them, then continue the rebase.", "Abort rebase"),
            render(OperationInProgress(Operation.REBASE, 2)).texts,
        )
        assertEquals(
            listOf("Cherry-pick in progress. Commit to finish it.", "Abort cherry-pick"),
            render(OperationInProgress(Operation.CHERRY_PICK, 0)).texts,
        )
        assertEquals(1, render(OperationInProgress(Operation.MERGE, 1), click = "Abort merge").aborts)
    }

    private data class Aborted(val asked: ConfirmableAction, val actions: List<StatusAction>)

    /** Aborts what [repositoryState] is in the middle of, and confirms when asked. */
    private fun abort(repositoryState: RepositoryState): Aborted {
        val state = StatusState(repositoryState = repositoryState, unstaged = listOf(conflicted, modified))
        val actions = mutableListOf<StatusAction>()
        var asked: ConfirmableAction? = null

        abortOperationInProgress(state, onAction = { actions += it }) { action, onConfirm ->
            asked = action
            onConfirm()
        }

        return Aborted(checkNotNull(asked), actions)
    }

    private data class Rendered(val texts: List<String>, val aborts: Int)

    /** Renders the banner offscreen, and clicks the element whose text is [click]. */
    private fun render(inProgress: OperationInProgress, click: String? = null): Rendered {
        var aborts = 0
        val scene = ImageComposeScene(width = 1000, height = 100, density = Density(1f)) {
            AppTheme {
                OperationInProgressBanner(inProgress, onAbort = { aborts++ })
            }
        }

        try {
            scene.render()

            val nodes = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            if (click != null) {
                val button = nodes.single { node ->
                    node.config.getOrNull(SemanticsActions.OnClick) != null &&
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == click }
                }
                checkNotNull(button.config[SemanticsActions.OnClick].action).invoke()
            }

            val texts = nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

            return Rendered(texts.distinct(), aborts)
        } finally {
            scene.close()
        }
    }
}
