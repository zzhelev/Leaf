// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.AgentMarker
import dev.app.leaf.domain.models.AgentSessionLock
import dev.app.leaf.domain.models.AgentTool
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeBranchUser
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeLabelRule
import dev.app.leaf.domain.models.WorktreeOrigin
import dev.app.leaf.domain.worktrees.WorktreeHeadLabel
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.dialogs.settings.WorktreeLabelsSetting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/** The tags of the tools that made worktrees, on rows, badges and tooltips, and their rules in Settings. */
class AgentTagsTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `a row shows its tool's tag right after the name, which ellipsizes first`() {
        val origin = WorktreeOrigin.InToolFolder(AgentTool.CLAUDE_CODE, "<repo>/.claude/worktrees/*")
        val row = row(worktree("/repo/.claude/worktrees/a-rather-long-worktree-name-that-cannot-fit"), origin)

        render(width = 220) { WorktreeRowLines(row, modifier = Modifier.width(200.dp)) }

        val name = node(row.name)
        val tag = node("Claude")

        assertTrue(tag.boundsInWindow.left >= name.boundsInWindow.right, "the tag follows the name")
        assertTrue(tag.boundsInWindow.right <= 200f, "the tag stays in the row")
    }

    @Test
    fun `a short name keeps the tag next to it, not at the row's end`() {
        val row = row(worktree("/home/ann/.codex/worktrees/a1b2/shop"), WorktreeOrigin.Marked(AgentMarker.CODEX))

        render(width = 420) { WorktreeRowLines(row, modifier = Modifier.width(400.dp)) }

        // "shop" is about 30 px wide: the tag follows it, far from the row's end
        assertTrue(node("Codex").boundsInWindow.left < 80f, node("Codex").boundsInWindow.toString())
    }

    @Test
    fun `the tooltip says who made the worktree and how Leaf knows`() {
        assertTooltipHas(
            WorktreeOrigin.Marked(AgentMarker.CODEX),
            "Made by Codex: its git dir has codex-thread.json",
        )
        assertTooltipHas(
            WorktreeOrigin.Marked(AgentMarker.CLAUDE_DESKTOP),
            "Made by the Claude desktop app: its git dir has claude-desktop-worktree",
        )
        assertTooltipHas(
            WorktreeOrigin.InToolFolder(AgentTool.CURSOR, "~/.cursor/worktrees/*/*"),
            "Made by Cursor: its folder matches ~/.cursor/worktrees/*/*",
        )
        assertTooltipHas(
            WorktreeOrigin.Labelled(WorktreeLabelRule("Aider", "~/aider/*")),
            "Labelled Aider: its folder matches ~/aider/*",
        )
    }

    @Test
    fun `the tooltip says whether a Claude Code session holding the lock still runs, in place of its reason`() {
        val reason = "claude session busy (pid 1 start Wed Oct 14 07:17:40 2026)"
        val running = worktree("/wt", locked = reason, agentLock = AgentSessionLock(AgentTool.CLAUDE_CODE, "busy", true))
        val ended = running.copy(agentLock = AgentSessionLock(AgentTool.CLAUDE_CODE, "busy", false))

        val runningText = tooltip(info(running))
        val endedText = tooltip(info(ended))

        assertTrue("Locked by a Claude Code session that is still running" in runningText, runningText)
        assertTrue("Locked by a Claude Code session that has ended" in endedText, endedText)
        assertFalse(reason in runningText)
        assertTrue("Locked: agent running" in tooltip(info(worktree("/wt", locked = "agent running"))))
    }

    @Test
    fun `a branch's badge shows the tag where the worktree's name would repeat the branch`() {
        val agent = info(
            worktree("/repo/.claude/worktrees/fix-login"),
            WorktreeOrigin.InToolFolder(AgentTool.CLAUDE_CODE, "<repo>/.claude/worktrees/*"),
        )

        render(width = 400) { Badge("claude/fix-login", agent) }

        assertTrue("Claude" in texts(), texts().toString())
        assertFalse("fix-login" in texts())
    }

    @Test
    fun `a branch's badge keeps the worktree's name when it says more than the branch`() {
        val agent = info(
            worktree("/repo/.claude/worktrees/busy-jang"),
            WorktreeOrigin.InToolFolder(AgentTool.CLAUDE_CODE, "<repo>/.claude/worktrees/*"),
        )

        render(width = 400) { Badge("feature/login", agent) }

        assertTrue("busy-jang" in texts(), texts().toString())
        assertFalse("Claude" in texts())
    }

    @Test
    fun `Settings lists the user's rules, then the built-in ones, and adds and removes rules`() {
        val saved = mutableListOf<List<WorktreeLabelRule>>()
        val aider = WorktreeLabelRule("Aider", "~/aider/*")

        render(width = 700, height = 900) { WorktreeLabelsSetting(listOf(aider)) { saved += it } }

        assertTrue("Aider" in editableTexts() && "~/aider/*" in editableTexts(), editableTexts().toString())
        assertTrue("<repo>/.claude/worktrees/*" in texts() && "~/.codex/worktrees/*/*" in texts())

        click(node("Add label"))
        assertEquals(listOf(aider, WorktreeLabelRule("", "")), saved.last())

        click(nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Remove this rule") == true })
        assertEquals(listOf(WorktreeLabelRule("", "")), saved.last())
    }

    @Composable
    private fun Badge(branch: String, info: WorktreeInfo) {
        BranchNameWithWorktree(
            text = branch,
            fontWeight = FontWeight.Normal,
            branchName = "refs/heads/$branch",
            users = BranchWorktreeUsers(listOf(WorktreeBranchUser(info, WorktreeBranchUse.CheckedOut))),
            baseBranch = "refs/heads/main",
            hasTrailingLabel = false,
        )
    }

    private fun assertTooltipHas(origin: WorktreeOrigin, line: String) {
        val text = tooltip(info(worktree("/wt"), origin))

        assertTrue(line in text.lines(), text)
    }

    private fun tooltip(info: WorktreeInfo): String {
        var text = ""
        render(width = 10) { text = worktreeTooltip(info, baseBranch = "refs/heads/main") }
        scene?.close()
        scene = null

        return text
    }

    private fun render(width: Int, height: Int = 100, content: @Composable () -> Unit) {
        scene = ImageComposeScene(width = width, height = height) { AppTheme { content() } }.also { it.render() }
    }

    private fun click(node: SemanticsNode) {
        val scene = scene!!
        val position = Offset(node.boundsInWindow.center.x, node.boundsInWindow.center.y)

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

    private fun node(text: String) = nodes().first { text in it.texts() }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun editableTexts(): List<String> =
        nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun row(worktree: Worktree, origin: WorktreeOrigin?) = WorktreeRow(
        info = info(worktree, origin),
        name = worktree.path.substringAfterLast('/'),
        head = WorktreeHeadLabel.OnBranch("claude/x"),
        ageLabel = "2h",
    )

    private fun info(worktree: Worktree, origin: WorktreeOrigin? = null) =
        WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = null, origin = origin)

    private fun worktree(path: String, locked: String? = null, agentLock: AgentSessionLock? = null) = Worktree(
        path = path,
        headSha = HASH,
        branch = "refs/heads/claude/x",
        isMain = false,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = locked,
        prunable = null,
        agentLock = agentLock,
    )
}
