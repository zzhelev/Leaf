// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.ui.context_menu.ContextMenuElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorktreesSectionTest {
    @Test
    fun `shows commits ahead and behind, leaving out a direction without any`() {
        assertEquals("↑3 ↓1", AheadBehind(ahead = 3, behind = 1).compactText())
        assertEquals("↑2", AheadBehind(ahead = 2, behind = 0).compactText())
        assertEquals("↓4", AheadBehind(ahead = 0, behind = 4).compactText())
        assertEquals("", AheadBehind(ahead = 0, behind = 0).compactText())
    }

    @Test
    fun `names a local base branch without its prefix, and a remote one with its remote`() {
        assertEquals("main", baseBranchName("refs/heads/main"))
        assertEquals("feature/login", baseBranchName("refs/heads/feature/login"))
        assertEquals("origin/main", baseBranchName("refs/remotes/origin/main"))
        assertEquals("upstream/release/2.0", baseBranchName("refs/remotes/upstream/release/2.0"))
    }

    @Test
    fun `offers to switch to another worktree, and switches to it`() {
        var switched = 0
        val items = worktreeContextMenuItems(worktree("/wt"), onSwitchTo = { switched++ }, onCopyPath = {})

        assertEquals(listOf("Switch to worktree", "Copy path"), labels(items))

        items.first().onClick()
        assertEquals(1, switched)
    }

    @Test
    fun `doesn't offer to switch to the tab's worktree, a missing one or a bare repository`() {
        val worktrees = listOf(
            worktree("/repo").copy(isCurrent = true),
            worktree("/gone").copy(prunable = "gitdir file points to non-existent location"),
            worktree("/bare.git").copy(isBare = true, headSha = null),
        )

        for (worktree in worktrees) {
            val items = worktreeContextMenuItems(worktree, onSwitchTo = {}, onCopyPath = {})

            assertEquals(listOf("Copy path"), labels(items), worktree.path)
        }
    }

    private fun worktree(path: String) = Worktree(
        path = path,
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = "refs/heads/feature",
        isMain = false,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )

    /** The labels of [items], read in a composition as the menu reads them. */
    private fun labels(items: List<ContextMenuElement>): List<String> {
        var labels = emptyList<String>()
        val scene = ImageComposeScene(width = 10, height = 10) {
            labels = items.filterIsInstance<ContextMenuElement.ContextTextEntry>().map { it.composableLabel() }
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        return labels
    }
}
