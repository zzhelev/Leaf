// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.worktrees.WorktreeHeadLabel
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.theme.AppTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/** The toolbar's worktree picker, rendered offscreen and clicked like a user would. */
class WorktreePickerTest {
    private val main = row(worktree("/repo", "refs/heads/main", isMain = true), "repo", "main")
    private val current = row(
        worktree("/work/fix-auth", "refs/heads/feature/auth", isCurrent = true),
        "fix-auth",
        "feature/auth",
    )
    private val agent = row(
        worktree("/repo/.claude/worktrees/busy-jang", "refs/heads/claude/busy-jang"),
        "busy-jang",
        "claude/busy-jang",
    )
    private val missing = row(
        worktree("/work/old-spike", "refs/heads/spike", prunable = "gitdir file points to non-existent location"),
        "old-spike",
        "spike",
    )

    private val switchedTo = mutableListOf<Worktree>()
    private var newWorktrees = 0
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `shows the tab's worktree and what it has checked out`() {
        render(main, current, agent, missing)

        assertEquals(listOf("fix-auth", "feature/auth"), texts())
    }

    @Test
    fun `shows nothing when the list doesn't have the tab's worktree yet`() {
        render(main, agent)

        assertEquals(emptyList<String>(), texts())
    }

    @Test
    fun `lists every worktree, and switches to the one picked`() {
        render(main, current, agent, missing)

        click("fix-auth")
        assertTrue(texts().containsAll(listOf("Worktrees", "repo", "busy-jang", "old-spike")), texts().toString())

        click("busy-jang")

        assertEquals(listOf(agent.info.worktree), switchedTo)
        assertEquals(listOf("fix-auth", "feature/auth"), texts(), "the list closed")
    }

    @Test
    fun `doesn't switch to a worktree whose folder is missing`() {
        render(main, current, agent, missing)

        click("fix-auth")
        click("old-spike")

        assertEquals(emptyList<Worktree>(), switchedTo)
    }

    @Test
    fun `ends with a row that opens the new worktree dialog, and closes the list`() {
        render(main, current, agent)

        click("fix-auth")
        assertEquals("New worktree…", texts().last())

        click("New worktree…")

        assertEquals(1, newWorktrees)
        assertEquals(emptyList<Worktree>(), switchedTo)
        assertEquals(listOf("fix-auth", "feature/auth"), texts(), "the list closed")
    }

    private fun render(vararg rows: WorktreeRow) {
        scene = ImageComposeScene(width = 800, height = 600) {
            AppTheme {
                WorktreePicker(
                    state = WorktreePickerState(rows.toList(), baseBranch = "refs/heads/main"),
                    onSwitchTo = { switchedTo += it },
                    onNewWorktree = { newWorktrees++ },
                )
            }
        }.also { it.render() }
    }

    /** Clicks the middle of the first text that reads [text], then lets the scene settle. */
    private fun click(text: String) {
        val scene = scene!!
        val bounds = nodes().first { it.texts().contains(text) }.boundsInWindow
        val position = Offset(bounds.center.x, bounds.center.y)

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        scene.render()
        scene.render()
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun row(worktree: Worktree, name: String, branch: String) = WorktreeRow(
        info = WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = null),
        name = name,
        head = WorktreeHeadLabel.OnBranch(branch),
        ageLabel = null,
    )

    private fun worktree(
        path: String,
        branch: String,
        isMain: Boolean = false,
        isCurrent: Boolean = false,
        prunable: String? = null,
    ) = Worktree(
        path = path,
        headSha = HASH,
        branch = branch,
        isMain = isMain,
        isCurrent = isCurrent,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = prunable,
    )
}
