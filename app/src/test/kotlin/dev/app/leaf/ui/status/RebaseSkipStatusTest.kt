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
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.rebase.RebaseStop
import dev.app.leaf.domain.rebase.SkippedCommitsNotice
import dev.app.leaf.domain.rebase.SkippedRebaseCommit
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.dialogs.SkippedCommitsContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A rebase's Skip of the commit that it stopped at (fork-only): when it's offered, what blocks it, its place between
 * Abort and Continue, and the notice of the commits that it left out (`SkippingRebaseCommitsTest` in data runs git).
 */
class RebaseSkipStatusTest {
    private val stop = RebaseStop("a4880d17".padEnd(40, '0'), "Show the unread bar", "fix/unread-bar", isEditStop = false)
    private val conflicting = StatusEntry("a.txt", StatusType.CONFLICTING)
    private val staged = StatusEntry("b.txt", StatusType.MODIFIED, EntryType.STAGED)
    private val stagedThenEdited = StatusEntry("b.txt", StatusType.MODIFIED)
    private val other = StatusEntry("c.txt", StatusType.MODIFIED)
    private val removed = StatusEntry("d.txt", StatusType.REMOVED)
    private val untracked = StatusEntry("notes.txt", StatusType.ADDED)

    private fun state(
        repositoryState: RepositoryState = RepositoryState.REBASING_MERGE,
        rebaseStop: RebaseStop? = stop,
        staged: List<StatusEntry> = emptyList(),
        unstaged: List<StatusEntry> = emptyList(),
    ) = StatusState(
        isLoading = false,
        staged = staged,
        unstaged = unstaged,
        commitMessage = TextFieldValue(""),
        repositoryState = repositoryState,
        rebaseStop = rebaseStop,
    )

    @Test
    fun `Skip is offered at a commit that the rebase stopped at, of either engine, not at an edit stop`() {
        assertTrue(state(unstaged = listOf(conflicting)).canSkipRebaseCommit)
        assertTrue(state(repositoryState = RepositoryState.REBASING_INTERACTIVE, staged = listOf(staged)).canSkipRebaseCommit)
        assertFalse(state(rebaseStop = stop.copy(isEditStop = true)).canSkipRebaseCommit, "An edit stop")
        assertFalse(state(rebaseStop = null).canSkipRebaseCommit, "No commit to name")
        assertFalse(state(repositoryState = RepositoryState.SAFE).canSkipRebaseCommit)
    }

    @Test
    fun `changes to other tracked files block it, the commit's own files and untracked ones don't`() {
        assertEquals(0, state(staged = listOf(staged), unstaged = listOf(conflicting, stagedThenEdited, untracked)).rebaseSkipOtherChanges)
        assertEquals(2, state(staged = listOf(staged), unstaged = listOf(conflicting, other, removed)).rebaseSkipOtherChanges)
        assertEquals(2, state(staged = listOf(staged), unstaged = listOf(conflicting)).skipDiscards)
    }

    @Test
    fun `the buttons are Abort, Skip and Continue, and a blocked Skip takes no click`(): Unit =
        runBlocking(Dispatchers.Swing) {
            assertEquals(Buttons(listOf("Abort", "Skip", "Continue"), listOf("Abort", "Skip", "Continue")), buttons(0))
            assertEquals(Buttons(listOf("Abort", "Skip", "Continue"), listOf("Abort", "Continue")), buttons(2))
        }

    @Test
    fun `the notice lists what was left out, and its buttons cherry-pick them back or close it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val notice = SkippedCommitsNotice(
                "fix/unread-bar",
                listOf(
                    SkippedRebaseCommit("a4880d17".padEnd(40, '0'), "Show the unread bar", "fix/unread-bar"),
                    SkippedRebaseCommit("3f9d27c1".padEnd(40, '0'), "Count unread messages", "fix/unread-bar"),
                ),
            )
            val calls = mutableListOf<String>()
            val texts = render(notice, calls, click = "Cherry-pick them back")

            assertTrue("Commits left out of fix/unread-bar" in texts, texts.toString())
            assertTrue(
                "The rebase ended without the 2 commits that you skipped. They're no longer on the branch, but still in the repository:" in
                    texts,
                texts.toString(),
            )
            assertTrue("a4880d1  Show the unread bar\n3f9d27c  Count unread messages" in texts, texts.toString())
            assertEquals(listOf("back"), calls)

            render(notice.copy(commits = notice.commits.take(1), branch = null), calls, click = "OK").let { one ->
                assertTrue("Commits left out of the rebase" in one, one.toString())
                assertTrue("Cherry-pick it back" in one, one.toString())
            }
            assertEquals(listOf("back", "ok"), calls)
        }

    private data class Buttons(val texts: List<String>, val clickable: List<String>)

    private fun buttons(skipBlockedBy: Int): Buttons = scene(330, 60, {
        StoppedOperationButtons(
            showsContinue = true,
            continueEnabled = true,
            canSkip = true,
            onAbort = {},
            onContinue = {},
            onSkip = {},
            skipBlockedBy = skipBlockedBy,
        )
    }) { nodes ->
        val withText = nodes.filter { it.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() }
            .filter { node -> node.children.none { it.config.getOrNull(SemanticsProperties.Text) != null } }
            .sortedBy { it.boundsInRoot.left }
        val clickable = nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .filter { it.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() }
            .sortedBy { it.boundsInRoot.left }

        Buttons(withText.map { it.text() }.distinct(), clickable.map { it.text() })
    }

    private fun render(notice: SkippedCommitsNotice, calls: MutableList<String>, click: String): List<String> =
        scene(700, 400, {
            SkippedCommitsContent(notice, onCherryPickBack = { calls += "back" }, onAccept = { calls += "ok" })
        }) { nodes ->
            val button = nodes.single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == click }
            }
            checkNotNull(button.config[SemanticsActions.OnClick].action).invoke()

            nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        }

    private fun SemanticsNode.text() = config[SemanticsProperties.Text].joinToString { it.text }

    private fun <T> scene(
        width: Int,
        height: Int,
        content: @androidx.compose.runtime.Composable () -> Unit,
        read: (List<SemanticsNode>) -> T,
    ): T {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            AppTheme { content() }
        }

        try {
            scene.render()

            val nodes = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return read(nodes)
        } finally {
            scene.close()
        }
    }
}
