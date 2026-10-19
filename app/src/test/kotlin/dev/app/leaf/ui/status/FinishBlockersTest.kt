// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.ImageComposeScene
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun untracked(path: String = "build.log") = StatusEntry(path, StatusType.ADDED)
private fun modified(path: String = "README.md") = StatusEntry(path, StatusType.MODIFIED)
private fun removed(path: String = "old.txt") = StatusEntry(path, StatusType.REMOVED)
private fun conflicting(path: String = "notes.txt") = StatusEntry(path, StatusType.CONFLICTING)
private fun staged(path: String = "src/Main.kt") = StatusEntry(path, StatusType.MODIFIED, EntryType.STAGED)

private val merging = listOf(RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED)
private val cherryPicking = listOf(RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED)
private val reverting = listOf(RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED)
private val rebasing = listOf(
    RepositoryState.REBASING,
    RepositoryState.REBASING_REBASING,
    RepositoryState.REBASING_MERGE,
    RepositoryState.REBASING_INTERACTIVE,
)

private fun state(
    repositoryState: RepositoryState,
    staged: List<StatusEntry> = emptyList(),
    unstaged: List<StatusEntry> = emptyList(),
    message: String = "",
    isAmend: Boolean = false,
    hasPreviousCommits: Boolean = true,
    rebaseInteractiveState: RebaseInteractiveState = RebaseInteractiveState.None,
    isAmendRebaseInteractive: Boolean = false,
) = StatusState(
    isLoading = false,
    staged = staged,
    unstaged = unstaged,
    commitMessage = TextFieldValue(message),
    previousCommitMessage = "Previous".takeIf { hasPreviousCommits },
    repositoryState = repositoryState,
    isAmend = isAmend,
    rebaseInteractiveState = rebaseInteractiveState,
    isAmendRebaseInteractive = isAmendRebaseInteractive,
)

/**
 * Why the commit field's primary button is disabled (Gitnuro#120, #124). git's own rules were probed on git 2.54
 * (`RebaseWithUnstagedFilesTest`): a merge and a cherry-pick finish with unstaged and untracked files around, and a
 * rebase's Continue refuses unstaged changes to tracked files only.
 */
class FinishBlockersTest {
    // --- Merge

    @Test
    fun `a merge isn't blocked by files that aren't staged`() {
        for (repositoryState in merging) {
            val files = listOf(untracked(), untracked("out/x.o"), modified(), removed())

            assertNull(state(repositoryState, unstaged = files).finishBlocker(), "$repositoryState")
            assertNull(state(repositoryState, staged = listOf(staged()), unstaged = files).finishBlocker())
        }
    }

    @Test
    fun `a merge is allowed with nothing staged, as git makes the merge commit anyway`() {
        // The conflicts were all resolved to our side: the merge commit still has two parents (Gitnuro#120)
        for (repositoryState in merging) {
            assertNull(state(repositoryState).finishBlocker(), "$repositoryState")
            assertNull(state(repositoryState, message = "Merge branch 'agent1'").finishBlocker())
        }
    }

    @Test
    fun `a merge is blocked by the files that still have conflicts, however many other files there are`() {
        for (repositoryState in merging) {
            assertEquals(
                FinishBlocker.Conflicts(1),
                state(repositoryState, unstaged = listOf(conflicting(), untracked())).finishBlocker(),
            )
            assertEquals(
                FinishBlocker.Conflicts(2),
                state(repositoryState, staged = listOf(staged()), unstaged = listOf(conflicting("a"), conflicting("b"), modified()))
                    .finishBlocker(),
            )
        }
    }

    // --- Cherry-pick and revert

    @Test
    fun `a cherry-pick or a revert isn't blocked by files that aren't staged`() {
        for (repositoryState in cherryPicking + reverting) {
            val files = listOf(untracked(), modified(), removed())

            assertNull(
                state(repositoryState, staged = listOf(staged()), unstaged = files, message = "Pick it").finishBlocker(),
                "$repositoryState",
            )
        }
    }

    @Test
    fun `a cherry-pick or a revert needs a message that isn't blank`() {
        for (repositoryState in cherryPicking + reverting) {
            assertEquals(
                FinishBlocker.EmptyMessage,
                state(repositoryState, staged = listOf(staged()), message = "").finishBlocker(),
                "$repositoryState",
            )
            assertEquals(
                FinishBlocker.EmptyMessage,
                state(repositoryState, staged = listOf(staged()), message = "  \n ").finishBlocker(),
            )
        }
    }

    @Test
    fun `a cherry-pick or a revert needs something staged, as JGit refuses an empty commit there`() {
        for (repositoryState in cherryPicking + reverting) {
            assertEquals(
                FinishBlocker.NothingStaged,
                state(repositoryState, unstaged = listOf(untracked(), modified()), message = "Pick it").finishBlocker(),
                "$repositoryState",
            )
        }
    }

    @Test
    fun `a cherry-pick or a revert says conflicts first, then the message, then nothing staged`() {
        for (repositoryState in cherryPicking + reverting) {
            assertEquals(
                FinishBlocker.Conflicts(1),
                state(repositoryState, unstaged = listOf(conflicting())).finishBlocker(),
                "$repositoryState",
            )
            assertEquals(FinishBlocker.EmptyMessage, state(repositoryState).finishBlocker())
            assertEquals(FinishBlocker.NothingStaged, state(repositoryState, message = "m").finishBlocker())
        }
    }

    // --- Rebase

    @Test
    fun `a rebase's Continue isn't blocked by untracked files`() {
        for (repositoryState in rebasing) {
            val files = listOf(untracked(), untracked("out/x.o"))

            assertNull(state(repositoryState, staged = listOf(staged()), unstaged = files).rebaseBlocker(), "$repositoryState")
            assertNull(state(repositoryState, staged = listOf(staged()), unstaged = files).finishBlocker())
        }
    }

    @Test
    fun `a rebase's Continue is blocked by unstaged changes to tracked files, as git refuses them`() {
        for (repositoryState in rebasing) {
            assertEquals(
                FinishBlocker.UnstagedChanges(1),
                state(repositoryState, staged = listOf(staged()), unstaged = listOf(modified(), untracked())).finishBlocker(),
                "$repositoryState",
            )
            assertEquals(
                FinishBlocker.UnstagedChanges(2),
                state(repositoryState, staged = listOf(staged()), unstaged = listOf(modified(), removed(), untracked()))
                    .finishBlocker(),
            )
        }
    }

    @Test
    fun `a rebase's Continue is blocked by a file that is staged and changed again`() {
        // The file is in both lists: staged for the commit, and modified on top of it. git refuses (S8, S9).
        val state = state(
            RepositoryState.REBASING_MERGE,
            staged = listOf(staged("a.txt")),
            unstaged = listOf(modified("a.txt")),
        )

        assertEquals(FinishBlocker.UnstagedChanges(1), state.finishBlocker())
    }

    @Test
    fun `a rebase's Continue says conflicts before unstaged changes, and counts each`() {
        for (repositoryState in rebasing) {
            assertEquals(
                FinishBlocker.Conflicts(2),
                state(repositoryState, unstaged = listOf(conflicting("a"), conflicting("b"), modified(), untracked()))
                    .finishBlocker(),
                "$repositoryState",
            )
        }
    }

    @Test
    fun `a rebase offers Skip, not Continue, when only untracked files or nothing is left`() {
        for (repositoryState in rebasing) {
            // The commit being applied came out empty. JGit's continue does nothing there and git's apply backend
            // refuses it, and Skip loses no work.
            assertFalse(state(repositoryState).showsContinue(), "$repositoryState")
            assertFalse(state(repositoryState, unstaged = listOf(untracked(), untracked("b"))).showsContinue())
        }
    }

    @Test
    fun `a rebase offers Continue when something is staged, or something blocks it`() {
        for (repositoryState in rebasing) {
            assertTrue(state(repositoryState, staged = listOf(staged())).showsContinue(), "$repositoryState")
            assertTrue(state(repositoryState, staged = listOf(staged()), unstaged = listOf(untracked())).showsContinue())
            // Disabled, with its reason, rather than Skip, which would discard the changes
            assertTrue(state(repositoryState, unstaged = listOf(modified())).showsContinue())
            assertTrue(state(repositoryState, unstaged = listOf(removed())).showsContinue())
            assertTrue(state(repositoryState, unstaged = listOf(conflicting())).showsContinue())
        }
    }

    @Test
    fun `a rebase stopped to amend offers Continue with an amended message, even with nothing staged`() {
        val stop = RebaseInteractiveState.ProcessingCommits(commitToAmendId = "abc1234")

        fun amending(message: String, amend: Boolean, rebaseState: RebaseInteractiveState = stop) = state(
            RepositoryState.REBASING_INTERACTIVE,
            message = message,
            rebaseInteractiveState = rebaseState,
            isAmendRebaseInteractive = amend,
        )

        assertTrue(amending("Reworded", amend = true).showsContinue())
        assertFalse(amending("", amend = true).showsContinue())
        assertFalse(amending("Reworded", amend = false).showsContinue())
        // A stop that can't be amended
        assertFalse(
            amending("Reworded", amend = true, RebaseInteractiveState.ProcessingCommits(commitToAmendId = null)).showsContinue()
        )
        assertTrue(amending("m", amend = true).isAmenableRebaseInteractive)
        assertFalse(state(RepositoryState.SAFE, rebaseInteractiveState = stop).isAmenableRebaseInteractive)
    }

    // --- Commit and Amend, outside an operation

    @Test
    fun `Commit needs a message and staged files, and says which is missing`() {
        assertNull(state(RepositoryState.SAFE, staged = listOf(staged()), message = "Fix").finishBlocker())
        assertEquals(
            FinishBlocker.NothingStaged,
            state(RepositoryState.SAFE, unstaged = listOf(modified()), message = "Fix").finishBlocker(),
        )
        assertEquals(
            FinishBlocker.EmptyMessage,
            state(RepositoryState.SAFE, staged = listOf(staged())).finishBlocker(),
        )
        // Neither: the message comes first
        assertEquals(FinishBlocker.EmptyMessage, state(RepositoryState.SAFE).finishBlocker())
    }

    @Test
    fun `Commit isn't blocked by files that aren't staged`() {
        assertNull(
            state(
                RepositoryState.SAFE,
                staged = listOf(staged()),
                unstaged = listOf(untracked(), modified()),
                message = "Fix",
            ).finishBlocker()
        )
    }

    @Test
    fun `Commit after a squash merge that stopped at conflicts is blocked by them`() {
        assertEquals(
            FinishBlocker.Conflicts(1),
            state(RepositoryState.SAFE, staged = listOf(staged()), unstaged = listOf(conflicting()), message = "Squash")
                .finishBlocker(),
        )
    }

    @Test
    fun `Amend needs a message and a commit to amend`() {
        assertNull(state(RepositoryState.SAFE, isAmend = true, message = "Reworded").finishBlocker())
        assertEquals(
            FinishBlocker.EmptyMessage,
            state(RepositoryState.SAFE, isAmend = true, message = "").finishBlocker(),
        )
        // No commit yet, and nothing to commit instead
        assertEquals(
            FinishBlocker.NothingStaged,
            state(RepositoryState.SAFE, isAmend = true, message = "Reworded", hasPreviousCommits = false).finishBlocker(),
        )
    }

    // --- The hint

    @Test
    fun `the commit field says nothing while it rests, with no message and nothing staged`() {
        assertNull(state(RepositoryState.SAFE).finishHint())
        assertNull(state(RepositoryState.SAFE, unstaged = listOf(modified(), untracked())).finishHint())
    }

    @Test
    fun `the commit field says what is missing once the user has started`() {
        assertEquals(
            FinishBlocker.NothingStaged,
            state(RepositoryState.SAFE, message = "Fix").finishHint(),
        )
        assertEquals(
            FinishBlocker.EmptyMessage,
            state(RepositoryState.SAFE, staged = listOf(staged())).finishHint(),
        )
        assertNull(state(RepositoryState.SAFE, staged = listOf(staged()), message = "Fix").finishHint())
    }

    @Test
    fun `an amend with no message gets a hint, as the user has started`() {
        assertEquals(
            FinishBlocker.EmptyMessage,
            state(RepositoryState.SAFE, isAmend = true).finishHint(),
        )
    }

    @Test
    fun `during an operation the hint is always there while the button is disabled, and never when it works`() {
        for (repositoryState in merging + cherryPicking + reverting + rebasing) {
            val bare = state(repositoryState)

            assertEquals(bare.finishBlocker(), bare.finishHint(), "$repositoryState")
        }

        assertEquals(FinishBlocker.EmptyMessage, state(RepositoryState.CHERRY_PICKING, staged = listOf(staged())).finishHint())
        assertNull(state(RepositoryState.MERGING, unstaged = listOf(untracked())).finishHint())
        assertNull(state(RepositoryState.REBASING_MERGE, staged = listOf(staged()), unstaged = listOf(untracked())).finishHint())
    }

    // --- All of it, against the rules as they are said above

    private val sampleFiles = listOf(
        emptyList(),
        listOf(untracked()),
        listOf(modified()),
        listOf(removed()),
        listOf(conflicting()),
        listOf(untracked(), modified(), conflicting()),
    )
    private val sampleStaged = listOf(emptyList(), listOf(staged()))
    private val sampleMessages = listOf("", "  ", "Fix")

    private fun everyState(repositoryStates: List<RepositoryState>, check: (StatusState) -> Unit) {
        for (repositoryState in repositoryStates) for (staged in sampleStaged) for (unstaged in sampleFiles) {
            for (message in sampleMessages) for (amend in listOf(false, true)) for (previous in listOf(false, true)) {
                check(
                    state(
                        repositoryState,
                        staged = staged,
                        unstaged = unstaged,
                        message = message,
                        isAmend = amend,
                        hasPreviousCommits = previous,
                    )
                )
            }
        }
    }

    @Test
    fun `every state, a button works exactly when its rules are met`() {
        everyState(merging) { state ->
            assertEquals(state.unstaged.none { it.statusType == StatusType.CONFLICTING }, state.finishBlocker() == null)
        }

        everyState(cherryPicking + reverting) { state ->
            val works = state.unstaged.none { it.statusType == StatusType.CONFLICTING } &&
                    state.commitMessage.text.isNotBlank() &&
                    state.staged.isNotEmpty()

            assertEquals(works, state.finishBlocker() == null, "${state.repositoryState} ${state.unstaged}")
        }

        everyState(rebasing) { state ->
            val works = state.unstaged.none { it.statusType != StatusType.ADDED }

            assertEquals(works, state.finishBlocker() == null, "${state.repositoryState} ${state.unstaged}")
        }

        // The rule that StatusPane used before, which this keeps outside an operation
        everyState(listOf(RepositoryState.SAFE, RepositoryState.BISECTING)) { state ->
            val message = state.commitMessage.text.isNotEmpty()
            val noConflicts = state.unstaged.none { it.statusType == StatusType.CONFLICTING }
            val works = (message && state.staged.isNotEmpty() && noConflicts) ||
                    (state.isAmend && message && state.hasPreviousCommits)

            assertEquals(works, state.finishBlocker() == null)
        }
    }

    @Test
    fun `every state, the reason given is true`() {
        everyState(merging + cherryPicking + reverting + rebasing + RepositoryState.SAFE) { state ->
            val conflicts = state.unstaged.count { it.statusType == StatusType.CONFLICTING }
            val tracked = state.unstaged.count {
                it.statusType == StatusType.MODIFIED || it.statusType == StatusType.REMOVED
            }

            when (val blocker = state.finishBlocker()) {
                null -> Unit
                is FinishBlocker.Conflicts -> assertEquals(conflicts, blocker.files)
                is FinishBlocker.UnstagedChanges -> {
                    assertTrue(state.repositoryState.isRebasing, "only a rebase counts unstaged changes")
                    assertEquals(conflicts, 0)
                    assertEquals(tracked, blocker.files)
                }

                FinishBlocker.NothingStaged -> assertTrue(state.staged.isEmpty())
                FinishBlocker.EmptyMessage -> assertTrue(state.commitMessage.text.isBlank())
            }

            // The hint is the blocker or nothing, never another reason
            assertTrue(state.finishHint() == null || state.finishHint() == state.finishBlocker())
        }
    }

    // --- The hint's words

    @Test
    fun `the hint says each reason in words`() = runBlocking(Dispatchers.Swing) {
        assertEquals("Resolve the conflicts in 1 file first.", render(FinishBlocker.Conflicts(1)))
        assertEquals("Resolve the conflicts in 2 files first.", render(FinishBlocker.Conflicts(2)))
        assertEquals(
            "Stage or discard the changes to 1 tracked file to continue.",
            render(FinishBlocker.UnstagedChanges(1)),
        )
        assertEquals(
            "Stage or discard the changes to 3 tracked files to continue.",
            render(FinishBlocker.UnstagedChanges(3)),
        )
        assertEquals("Write a commit message.", render(FinishBlocker.EmptyMessage))
        assertEquals("Stage some changes to commit.", render(FinishBlocker.NothingStaged))
    }

    private fun render(blocker: FinishBlocker): String {
        val scene = ImageComposeScene(width = 600, height = 60, density = Density(1f)) {
            AppTheme { FinishHint(blocker) }
        }

        try {
            scene.render()

            val nodes = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                .distinct()
                .single()
        } finally {
            scene.close()
        }
    }
}
