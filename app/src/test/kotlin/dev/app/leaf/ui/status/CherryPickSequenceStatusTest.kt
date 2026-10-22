// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.cherrypick.CherryPickSequence
import dev.app.leaf.domain.models.EntryType
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The status pane while a cherry-pick of several commits that git stopped waits (fork-only): Continue or Skip, and
 * Abort, by git's rules (git 2.54, `CherryPickingBranchTest` in data runs them), and the banner.
 */
class CherryPickSequenceStatusTest {
    private val sequence = CherryPickSequence(listOf("dea00a5", "277db12"), noCommit = false)
    private val noCommit = sequence.copy(noCommit = true)
    private val conflicting = StatusEntry("a.txt", StatusType.CONFLICTING)
    private val staged = StatusEntry("a.txt", StatusType.MODIFIED, EntryType.STAGED)
    private val unstaged = StatusEntry("b.txt", StatusType.MODIFIED)
    private val untracked = StatusEntry("notes.txt", StatusType.ADDED)

    private fun state(
        sequence: CherryPickSequence? = this.sequence,
        repositoryState: RepositoryState = RepositoryState.CHERRY_PICKING,
        staged: List<StatusEntry> = emptyList(),
        unstaged: List<StatusEntry> = emptyList(),
        message: String = "fix: one",
    ) = StatusState(
        isLoading = false,
        staged = staged,
        unstaged = unstaged,
        commitMessage = TextFieldValue(message),
        repositoryState = repositoryState,
        cherryPickSequence = sequence,
    )

    @Test
    fun `conflicts block Continue, and an empty message when Continue commits the stopped commit`() {
        assertEquals(FinishBlocker.Conflicts(1), state(unstaged = listOf(conflicting)).finishBlocker())
        assertEquals(FinishBlocker.EmptyMessage(), state(staged = listOf(staged), message = "").finishBlocker())
        assertEquals(
            FinishBlocker.EmptyMessage(commentsOnly = true),
            state(staged = listOf(staged), message = "# only a comment").finishBlocker(),
        )
        assertNull(state(staged = listOf(staged)).finishBlocker())
    }

    @Test
    fun `unstaged and untracked files don't block it, as git goes on with them`() {
        assertNull(state(staged = listOf(staged), unstaged = listOf(unstaged, untracked)).finishBlocker())
    }

    @Test
    fun `Continue that commits nothing needs no message`() {
        // --no-commit stages the commits, and local changes in the way left no CHERRY_PICK_HEAD
        assertNull(state(sequence = noCommit, repositoryState = RepositoryState.SAFE, message = "").finishBlocker())
        assertNull(state(repositoryState = RepositoryState.SAFE, message = "").finishBlocker())
        assertFalse(state(sequence = noCommit).continueCommitsStoppedCommit)
        assertFalse(state(repositoryState = RepositoryState.SAFE).continueCommitsStoppedCommit)
        assertTrue(state().continueCommitsStoppedCommit)
    }

    @Test
    fun `Skip takes Continue's place when the stopped commit came out empty`() {
        assertFalse(state().showsContinue(), "Nothing staged: git says the cherry-pick is now empty")
        assertFalse(state(unstaged = listOf(untracked)).showsContinue())
        assertTrue(state(staged = listOf(staged)).showsContinue())
        assertTrue(state(unstaged = listOf(conflicting)).showsContinue(), "Continue, disabled, with its reason")
        assertTrue(state(sequence = noCommit, repositoryState = RepositoryState.SAFE).showsContinue())
        assertTrue(state(repositoryState = RepositoryState.SAFE).showsContinue())
    }

    @Test
    fun `the hint says why, as in any operation`() {
        assertEquals(FinishBlocker.Conflicts(1), state(unstaged = listOf(conflicting), message = "").finishHint())
        assertEquals(
            FinishBlocker.Conflicts(1),
            state(sequence = noCommit, repositoryState = RepositoryState.SAFE, unstaged = listOf(conflicting)).finishHint(),
            "Also where JGit reads no operation",
        )
    }

    @Test
    fun `the banner shows a cherry-pick, also where JGit reads none, and before a squash merge`() {
        assertEquals(
            OperationInProgress(Operation.CHERRY_PICK, 1, isSequence = true),
            operationInProgress(state(unstaged = listOf(conflicting))),
        )
        assertEquals(
            OperationInProgress(Operation.CHERRY_PICK, 0, isSequence = true),
            operationInProgress(
                state(sequence = noCommit, repositoryState = RepositoryState.SAFE).copy(isSquashMergeInProgress = true)
            ),
        )
        assertEquals(
            OperationInProgress(Operation.CHERRY_PICK, 0),
            operationInProgress(state(sequence = null)),
            "A single cherry-pick, as before",
        )
    }

    @Test
    fun `Abort asks first, then runs git's abort`() {
        val actions = mutableListOf<StatusAction>()
        var asked: ConfirmableAction? = null

        abortOperationInProgress(state(unstaged = listOf(conflicting)), onAction = { actions += it }) { action, onConfirm ->
            asked = action
            onConfirm()
        }

        assertEquals(ConfirmableAction.AbortOperation(Operation.CHERRY_PICK_SEQUENCE, 1), asked)
        assertEquals(
            listOf(StatusAction.AbortCherryPick, StatusAction.UpdateCommitMessage(TextFieldValue(""))),
            actions,
        )
    }

    @Test
    fun `the banner says to continue`(): Unit = runBlocking(Dispatchers.Swing) {
        assertEquals(
            listOf("Cherry-pick stopped at conflicts in 2 files. Resolve them, then continue it.", "Abort cherry-pick"),
            bannerTexts(OperationInProgress(Operation.CHERRY_PICK, 2, isSequence = true)),
        )
        assertEquals(
            listOf("Cherry-pick in progress. Continue it to pick the rest.", "Abort cherry-pick"),
            bannerTexts(OperationInProgress(Operation.CHERRY_PICK, 0, isSequence = true)),
        )
    }

    private fun bannerTexts(inProgress: OperationInProgress): List<String> {
        val scene = ImageComposeScene(width = 1000, height = 100, density = Density(1f)) {
            AppTheme {
                OperationInProgressBanner(inProgress, onAbort = {})
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

            return nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                .distinct()
        } finally {
            scene.close()
        }
    }
}
