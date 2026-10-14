// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.worktrees.WorktreeHeadLabel
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.theme.AppTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val DAY = 24 * 60 * 60 * 1000L

/** "merged" and "idle" on worktree rows and in their tooltips (Phase 4.3a). */
class StaleWorktreeMarkersTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `a merged worktree shows merged in place of its commits ahead and behind`() {
        val aheadBehind = AheadBehind(ahead = 2, behind = 1)

        render { WorktreeRowLines(row(info(aheadBehind, merged = MergedIntoBase.CHANGES))) }
        assertTrue("merged" in texts(), texts().toString())
        assertFalse("↑2 ↓1" in texts())

        render { WorktreeRowLines(row(info(aheadBehind))) }
        assertTrue("↑2 ↓1" in texts(), texts().toString())
        assertFalse("merged" in texts())
    }

    @Test
    fun `an idle worktree shows how long in place of its commit's age`() {
        render { WorktreeRowLines(row(info(), idleLabel = "3w")) }
        assertTrue("idle 3w" in texts(), texts().toString())
        assertFalse("2h" in texts())

        render { WorktreeRowLines(row(info())) }
        assertTrue("2h" in texts(), texts().toString())
    }

    @Test
    fun `a missing worktree says so rather than idle`() {
        val missing = info().let { it.copy(worktree = it.worktree.copy(prunable = "gitdir file points to nothing")) }

        render { WorktreeRowLines(row(missing, idleLabel = "3w")) }

        assertTrue("missing" in texts(), texts().toString())
        assertFalse("idle 3w" in texts())
    }

    @Test
    fun `the tooltip says how the work reached the base, and for how many days nothing changed`() {
        assertTrue("Its commits are in main" in tooltip(info(merged = MergedIntoBase.COMMITS)).lines())
        assertTrue("Its changes are in main (squash-merged)" in tooltip(info(merged = MergedIntoBase.CHANGES)).lines())

        val idle = tooltip(info(idleSince = System.currentTimeMillis() - 21 * DAY - 1000))
        assertTrue("Nothing changed in it for 21 days" in idle.lines(), idle)

        val plain = tooltip(info())
        assertFalse("merged" in plain || "Nothing changed" in plain, plain)
    }

    private fun tooltip(info: WorktreeInfo): String {
        var text = ""
        render { text = worktreeTooltip(info, baseBranch = "refs/heads/main") }

        return text
    }

    private fun render(content: @Composable () -> Unit) {
        scene?.close()
        scene = ImageComposeScene(width = 420, height = 100) {
            AppTheme { Box(Modifier.width(400.dp)) { content() } }
        }.also { it.render() }
    }

    private fun texts(): List<String> = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(false) }
        .flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun row(info: WorktreeInfo, idleLabel: String? = null) = WorktreeRow(
        info = info,
        name = "agent",
        head = WorktreeHeadLabel.OnBranch("agent"),
        ageLabel = "2h",
        idleLabel = idleLabel,
    )

    private fun info(
        aheadBehind: AheadBehind? = AheadBehind(ahead = 0, behind = 3),
        merged: MergedIntoBase? = null,
        idleSince: Long? = null,
    ) = WorktreeInfo(
        worktree = Worktree(
            path = "/repo/.claude/worktrees/agent",
            headSha = "0123456789abcdef0123456789abcdef01234567",
            branch = "refs/heads/agent",
            isMain = false,
            isCurrent = false,
            isDetached = false,
            isBare = false,
            locked = null,
            prunable = null,
        ),
        status = null,
        aheadBehindBase = aheadBehind,
        lastCommitTime = null,
        merged = merged,
        idleSince = idleSince,
    )
}
