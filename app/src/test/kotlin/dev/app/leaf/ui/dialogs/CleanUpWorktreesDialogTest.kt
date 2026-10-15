// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.AgentMarker
import dev.app.leaf.domain.models.AgentSessionLock
import dev.app.leaf.domain.models.AgentTool
import dev.app.leaf.domain.models.CleanupCandidate
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeCleanup
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeOrigin
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.candidates
import dev.app.leaf.domain.worktrees.checkedAtFirst
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val HEAD = "0123456789abcdef0123456789abcdef01234567"
private val CLEAN = WorktreeStatus(0, 0, 0, 0, upstream = null, upstreamAheadBehind = null)

/** What "Clean up merged worktrees" shows and runs, from its state (fork-only, Phase 4.3b). */
class CleanUpWorktreesDialogTest {
    private val home = System.getProperty("user.home")
    private val repo = "$home/dev/leaf"

    private val main = info(worktree(repo, "refs/heads/main", isMain = true))
    private val claude = info(
        worktree("$repo/.claude/worktrees/a1", "refs/heads/worktree-a1"),
        merged = MergedIntoBase.CHANGES,
        origin = WorktreeOrigin.Marked(AgentMarker.CLAUDE_CODE),
    )
    private val fix = info(worktree("$repo-fix", "refs/heads/fix"), merged = MergedIntoBase.COMMITS)
    private val old = info(worktree("$repo-old", "refs/heads/old", prunable = "gone"), status = null)
    private val dirty = info(
        worktree("$repo-db", "refs/heads/db"),
        status = CLEAN.copy(unstaged = 2, untracked = 1),
        merged = MergedIntoBase.COMMITS,
    )
    private val parser = info(
        worktree("$repo-parser", "refs/heads/parser", locked = "claude session p", running = true),
        merged = MergedIntoBase.COMMITS,
    )
    private val cleanup = WorktreeCleanup(
        WorktreeList(WorktreeBaseBranch(automatic = "refs/heads/main"), listOf(main, claude, fix, old, dirty, parser))
    )
    private val state = CleanUpWorktreesState(cleanup = cleanup, checked = cleanup.candidates.checkedAtFirst())

    @Test
    fun `lists why each worktree is offered, what to look at, and the commands`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(state).use { dialog ->
                val texts = dialog.texts()

                assertTrue("Clean up merged worktrees" in texts, texts.toString())
                assertTrue(
                    "Removes worktrees whose work is in main, and worktrees whose folder is missing." in texts,
                    texts.toString(),
                )
                for (text in listOf(
                    "a1", "Claude", "worktree-a1", "Its changes are in main (squash-merged)",
                    "leaf-fix", "Its commits are in main",
                    "leaf-old", "Its folder is missing. Its branch isn't merged into main, so it stays",
                    "leaf-db", "3 uncommitted changes. Checking it deletes them",
                    "leaf-parser", "In use by a Claude Code session that is still running",
                    "Also delete their branches",
                )) {
                    assertTrue(text in texts, "$text in $texts")
                }

                assertTrue(
                    listOf(
                        "cd ~/dev/leaf",
                        "git worktree remove .claude/worktrees/a1",
                        "git branch -D worktree-a1",
                        "git worktree remove ../leaf-fix",
                        "git branch -D fix",
                        "git worktree remove ../leaf-old",
                    ).joinToString("\n") in texts,
                    texts.toString(),
                )
                // The three ready worktrees', the one with changes', and the branches'. None for the one in use
                assertEquals(5, dialog.checkboxes())

                dialog.click("Remove 3 worktrees")
                assertEquals(listOf("confirm"), dialog.calls)
            }
        }

    @Test
    fun `checking a worktree with changes counts it, and forces its removal`(): Unit = runBlocking(Dispatchers.Swing) {
        val dbCandidate = state.candidates.single { it.info == dirty }

        DialogScene(state).use { dialog ->
            dialog.clickRow("leaf-db")

            assertEquals(listOf("check ${dbCandidate.worktree.path} true"), dialog.calls)
        }

        val checked = state.copy(checked = state.checked + dbCandidate.worktree.path)

        DialogScene(checked).use { dialog ->
            val texts = dialog.texts()

            assertTrue(dialog.isEnabled("Remove 4 worktrees"), texts.toString())
            assertTrue(texts.any { "git worktree remove --force ../leaf-db\ngit branch -D db" in it }, texts.toString())
        }
    }

    @Test
    fun `a worktree in use is never removed, even when its path is checked`() {
        val parserPath = parser.worktree.path
        val withParser = state.copy(checked = state.checked + parserPath)

        assertFalse(withParser.selected.any { it.worktree.path == parserPath })
        assertFalse("../leaf-parser" in withParser.steps.filterIsInstance<WorktreeStep.Git>().flatMap { it.args })
    }

    @Test
    fun `nothing checked runs nothing, and keeping the branches drops their commands`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val none = state.copy(checked = emptySet())

            assertFalse(none.canConfirm)

            DialogScene(none).use { dialog ->
                assertFalse(dialog.isEnabled("Remove"))
                assertFalse(dialog.texts().any { it.startsWith("cd ") })
            }

            val keepBranches = state.copy(deleteBranches = false)

            assertTrue(keepBranches.steps.filterIsInstance<WorktreeStep.Git>().none { it.args.first() == "branch" })
        }

    @Test
    fun `an agent's ended session is unlocked first, and an unknown lock is left unchecked`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val ended = info(
                worktree("$repo-ended", "refs/heads/ended", locked = "claude session e", running = false),
                merged = MergedIntoBase.COMMITS,
            )
            val usb = info(worktree("$repo-usb", "refs/heads/usb", locked = "on a USB drive"), merged = MergedIntoBase.COMMITS)
            val cleanup = WorktreeCleanup(
                WorktreeList(WorktreeBaseBranch(automatic = "refs/heads/main"), listOf(main, ended, usb)),
                recentChanges = mapOf("$repo-usb" to System.currentTimeMillis()),
            )
            val lockState = CleanUpWorktreesState(cleanup = cleanup, checked = cleanup.candidates.checkedAtFirst())

            DialogScene(lockState).use { dialog ->
                val texts = dialog.texts()

                assertTrue("Locked by a Claude Code session that has ended. It's unlocked first" in texts, texts.toString())
                assertTrue("Locked: on a USB drive. Checking it unlocks it first" in texts, texts.toString())
                assertTrue(
                    "Changed less than a minute ago. An agent may still be working in it" in texts,
                    texts.toString(),
                )
                assertTrue(
                    texts.any { "git worktree unlock ../leaf-ended\ngit worktree remove ../leaf-ended" in it },
                    texts.toString(),
                )
                assertTrue(dialog.isEnabled("Remove 1 worktree"))
            }
        }

    private fun info(
        worktree: Worktree,
        status: WorktreeStatus? = CLEAN,
        merged: MergedIntoBase? = null,
        origin: WorktreeOrigin? = null,
    ) = WorktreeInfo(worktree, status, aheadBehindBase = null, lastCommitTime = null, origin = origin, merged = merged)

    private fun worktree(
        path: String,
        branch: String,
        isMain: Boolean = false,
        locked: String? = null,
        prunable: String? = null,
        running: Boolean? = null,
    ) = Worktree(
        path = path,
        headSha = HEAD,
        branch = branch,
        isMain = isMain,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = locked,
        prunable = prunable,
        agentLock = running?.let { AgentSessionLock(AgentTool.CLAUDE_CODE, "session", isRunning = it) },
    )

    private inner class DialogScene(state: CleanUpWorktreesState) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 900, height = 1000, density = Density(1f)) {
            AppTheme {
                CleanUpWorktreesDialogContent(
                    state = state,
                    onCheckedChange = { candidate: CleanupCandidate, checked: Boolean ->
                        calls += "check ${candidate.worktree.path} $checked"
                    },
                    onDeleteBranchesChange = { calls += "branches $it" },
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

        /** How many checkboxes the dialog has. */
        fun checkboxes(): Int = nodes().count { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }

        /** Whether the button [text] takes clicks: `PrimaryButton` isn't clickable at all while it's disabled. */
        fun isEnabled(text: String): Boolean = buttons(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(buttons(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        /** Clicks the row of the worktree whose folder is [name], which toggles its checkbox. */
        fun clickRow(name: String) {
            val row = nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.ToggleableState) == null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name }
            }

            checkNotNull(row.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun buttons(text: String): List<SemanticsNode> = nodes().filter { node ->
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
