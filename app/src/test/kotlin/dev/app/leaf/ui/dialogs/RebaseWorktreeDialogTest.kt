// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRebase
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val REBASE = "Rebase"

/** What the dialog that rebases a worktree's branch onto the base shows, from its state (fork-only). */
class RebaseWorktreeDialogTest {
    private val home = System.getProperty("user.home")
    private val agent = Worktree(
        path = "$home/dev/leaf-agent1",
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = "refs/heads/agent1",
        isMain = false,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )

    @Test
    fun `shows what the rebase does and its commands, and rebases`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(RebaseWorktreeState(rebase())).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Rebase agent1 onto main" in texts, texts.toString())
            assertTrue(
                "Replays the 2 commits of \"agent1\" on top of the 3 new commits of \"main\"." in texts,
                texts.toString(),
            )
            assertTrue(
                listOf(
                    "cd ~/dev/leaf-agent1",
                    "git rebase --no-autostash main",
                    "# If it stops at conflicts, switch this tab to leaf-agent1",
                ).joinToString("\n") in texts,
                texts.toString(),
            )
            assertFalse(texts.any { it.contains("An agent may still be working") }, texts.toString())

            dialog.click(REBASE)

            assertEquals(listOf("confirm"), dialog.calls)
        }
    }

    @Test
    fun `counts one commit on each side in the singular`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(RebaseWorktreeState(rebase(commitCount = 1, baseCommitCount = 1))).use { dialog ->
            val texts = dialog.texts()

            assertTrue(
                "Replays the 1 commit of \"agent1\" on top of the 1 new commit of \"main\"." in texts,
                texts.toString(),
            )
        }
    }

    @Test
    fun `says that a branch without commits of its own moves up`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(RebaseWorktreeState(rebase(commitCount = 0))).use { dialog ->
            val texts = dialog.texts()

            assertTrue("\"agent1\" has no commits of its own, so it moves up to \"main\"." in texts, texts.toString())
        }
    }

    @Test
    fun `warns that an agent may still be working in the worktree`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(RebaseWorktreeState(rebase(lastChange = System.currentTimeMillis()))).use { dialog ->
            val texts = dialog.texts()

            assertTrue(
                "leaf-agent1 changed less than a minute ago. An agent may still be working in it." in texts,
                texts.toString(),
            )
            assertTrue(dialog.isEnabled(REBASE))
        }
    }

    @Test
    fun `refuses uncommitted changes, without the commands`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = RebaseWorktreeState(rebase(status = status(unstaged = 1, staged = 1, untracked = 5)))

        DialogScene(state).use { dialog ->
            val texts = dialog.texts()

            assertTrue(
                "leaf-agent1 has 2 uncommitted changes. Commit or stash them in that worktree first." in texts,
                texts.toString(),
            )
            assertFalse(texts.any { it.startsWith("cd ") }, texts.toString())
            assertFalse(dialog.isEnabled(REBASE))
        }
    }

    @Test
    fun `refuses a branch that already has the base's commits`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(RebaseWorktreeState(rebase(baseCommitCount = 0))).use { dialog ->
            val texts = dialog.texts()

            assertTrue("\"agent1\" already has every commit of \"main\"." in texts, texts.toString())
            assertFalse(dialog.isEnabled(REBASE))
        }
    }

    @Test
    fun `names the branches while the worktree is checked, and shows an error that stops it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(RebaseWorktreeState()).use { dialog ->
                val texts = dialog.texts()

                assertTrue("\"agent1\" onto \"main\"" in texts, texts.toString())
                assertTrue("Checking the worktree…" in texts, texts.toString())
                assertFalse(dialog.isEnabled(REBASE))
            }

            DialogScene(RebaseWorktreeState(error = GenericError("The worktree list couldn't be read"))).use { dialog ->
                assertTrue(dialog.texts().any { it.contains("The worktree list couldn't be read") })
                assertFalse(dialog.isEnabled(REBASE))
            }
        }

    private fun rebase(
        status: WorktreeStatus = status(),
        lastChange: Long? = null,
        commitCount: Int = 2,
        baseCommitCount: Int = 3,
    ) = WorktreeRebase(
        worktree = agent,
        branch = "refs/heads/agent1",
        base = "refs/heads/main",
        baseArgument = "main",
        status = status,
        lastChange = lastChange,
        commitCount = commitCount,
        baseCommitCount = baseCommitCount,
    )

    private fun status(staged: Int = 0, unstaged: Int = 0, untracked: Int = 0) =
        WorktreeStatus(staged, unstaged, untracked, conflicted = 0, upstream = null, upstreamAheadBehind = null)

    private inner class DialogScene(state: RebaseWorktreeState) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 800, height = 800, density = Density(1f)) {
            AppTheme {
                RebaseWorktreeDialogContent(
                    state = state,
                    worktree = agent,
                    base = "refs/heads/main",
                    onConfirm = { calls += "confirm" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** Whether the button [text] takes clicks: `PrimaryButton` isn't clickable at all while it's disabled. */
        fun isEnabled(text: String): Boolean = buttons(text).isNotEmpty()

        /** Clicks the clickable element whose text is [text]. */
        fun click(text: String) {
            checkNotNull(buttons(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun buttons(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
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
