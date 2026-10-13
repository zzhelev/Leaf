// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeMergeKind
import dev.app.leaf.ui.context_menu.ContextMenuElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `offers to switch to another worktree or open it in a new tab, and does`() {
        var switched = 0
        var openedInNewTab = 0
        val items = worktreeContextMenuItems(
            worktree("/wt"),
            onSwitchTo = { switched++ },
            onOpenInNewTab = { openedInNewTab++ },
            onCopyPath = {},
        )

        assertEquals(
            listOf("Switch to worktree", "Open in new tab", "Lock…", "Remove…", "Discard…", "Copy path"),
            labels(items),
        )

        items[0].onClick()
        assertEquals(1, switched)
        assertEquals(0, openedInNewTab)

        items[1].onClick()
        assertEquals(1, switched)
        assertEquals(1, openedInNewTab)
    }

    @Test
    fun `doesn't offer to switch to or open the tab's worktree, a missing one or a bare repository`() {
        val worktrees = listOf(
            worktree("/repo").copy(isCurrent = true, isMain = true),
            worktree("/agent").copy(isCurrent = true),
            worktree("/gone").copy(prunable = "gitdir file points to non-existent location"),
            worktree("/bare.git").copy(isMain = true, isBare = true, headSha = null),
        )

        for (worktree in worktrees) {
            val labels = labels(worktreeContextMenuItems(worktree, onSwitchTo = {}, onOpenInNewTab = {}, onCopyPath = {}))

            assertFalse("Switch to worktree" in labels, worktree.path)
            assertFalse("Open in new tab" in labels, worktree.path)
        }
    }

    @Test
    fun `offers nothing that removes, locks or prunes the main worktree`() {
        val main = worktree("/repo").copy(isMain = true)

        val items = worktreeContextMenuItems(main, onSwitchTo = {}, onOpenInNewTab = {}, onCopyPath = {})

        assertEquals(listOf("Switch to worktree", "Open in new tab", "Copy path"), labels(items))
        assertFalse(items.any { it is ContextMenuElement.ContextSeparator })
    }

    @Test
    fun `offers the tab's own worktree to remove, between separators`() {
        val items = worktreeContextMenuItems(
            worktree("/agent").copy(isCurrent = true),
            onSwitchTo = {},
            onOpenInNewTab = {},
            onCopyPath = {},
        )

        assertEquals(listOf("Lock…", "Remove…", "Discard…", "Copy path"), labels(items))
        assertEquals(ContextMenuElement.ContextSeparator, items[3])
        assertFalse(items.first() is ContextMenuElement.ContextSeparator)
    }

    @Test
    fun `offers to unlock a locked worktree, and to prune a missing one, and runs each on the worktree`() {
        val actions = mutableListOf<String>()
        val recording = WorktreeActions(
            onLock = { actions += "lock ${it.path}" },
            onUnlock = { actions += "unlock ${it.path}" },
            onRemove = { actions += "remove ${it.path}" },
            onDiscard = { actions += "discard ${it.path}" },
            onPrune = { actions += "prune" },
        )
        val worktree = worktree("/gone").copy(locked = "on a USB drive", prunable = "gitdir file points to non-existent location")

        val items = worktreeContextMenuItems(worktree, onSwitchTo = {}, onOpenInNewTab = {}, onCopyPath = {}, recording)

        assertEquals(listOf("Unlock", "Remove…", "Discard…", "Prune missing worktrees…", "Copy path"), labels(items))
        items.filterIsInstance<ContextMenuElement.ContextTextEntry>().dropLast(1).forEach { it.onClick() }
        assertEquals(listOf("unlock /gone", "remove /gone", "discard /gone", "prune"), actions)

        // Switch to worktree, Open in new tab, then Lock…
        val lockItem = worktreeContextMenuItems(worktree("/wt"), {}, {}, {}, recording)
            .filterIsInstance<ContextMenuElement.ContextTextEntry>()[2]
        assertEquals(listOf("Lock…"), labels(listOf(lockItem)))
        lockItem.onClick()
        assertEquals("lock /wt", actions.last())
    }

    @Test
    fun `offers to review the changes of a worktree on another branch than the base, first`() {
        val reviewed = mutableListOf<String>()
        val actions = WorktreeActions(onReview = { reviewed += it.path })

        val items = worktreeContextMenuItems(worktree("/wt"), {}, {}, {}, actions, baseBranch = "refs/heads/main")

        assertEquals("Review changes", labels(items).first())
        items.first().onClick()
        assertEquals(listOf("/wt"), reviewed)

        val onBase = worktree("/repo").copy(branch = "refs/heads/main", isMain = true)
        val detached = worktree("/wt").copy(branch = null, isDetached = true)

        for (worktree in listOf(onBase, detached)) {
            val labels = labels(worktreeContextMenuItems(worktree, {}, {}, {}, actions, baseBranch = "refs/heads/main"))
            assertFalse("Review changes" in labels, worktree.path)
        }

        assertFalse("Review changes" in labels(worktreeContextMenuItems(worktree("/wt"), {}, {}, {}, actions)))
    }

    @Test
    fun `offers to squash-merge or merge a worktree's branch into the base's local branch, after reviewing it`() {
        val merged = mutableListOf<Pair<String, WorktreeMergeKind>>()
        val actions = WorktreeActions(onMerge = { worktree, kind -> merged += worktree.path to kind })

        val items = worktreeContextMenuItems(
            worktree("/wt"),
            {},
            {},
            {},
            actions,
            baseBranch = "refs/remotes/origin/main",
            mergeTarget = "refs/heads/main",
        )

        assertEquals(listOf("Review changes", "Squash-merge into main…", "Merge into main…"), labels(items).take(3))
        items[1].onClick()
        items[2].onClick()
        assertEquals(listOf("/wt" to WorktreeMergeKind.SQUASH, "/wt" to WorktreeMergeKind.MERGE), merged)
    }

    @Test
    fun `offers no merge without a local branch to merge into, or for a worktree on it`() {
        val onTarget = worktree("/repo").copy(branch = "refs/heads/main", isMain = true)
        val withoutTarget = worktreeContextMenuItems(
            worktree("/wt"),
            {},
            {},
            {},
            baseBranch = "refs/remotes/origin/main",
            mergeTarget = null,
        )
        val forTarget = worktreeContextMenuItems(
            onTarget,
            {},
            {},
            {},
            baseBranch = "refs/remotes/origin/main",
            mergeTarget = "refs/heads/main",
        )

        for (items in listOf(withoutTarget, forTarget)) {
            assertFalse(labels(items).any { it.contains("merge", ignoreCase = true) }, labels(items).toString())
        }
    }

    @Test
    fun `offers to rebase a worktree's branch onto the base, after merging it`() {
        val rebased = mutableListOf<String>()
        val actions = WorktreeActions(onRebase = { worktree -> rebased += worktree.path })

        val items = worktreeContextMenuItems(
            worktree("/wt"),
            {},
            {},
            {},
            actions,
            baseBranch = "refs/remotes/origin/main",
            mergeTarget = "refs/heads/main",
        )

        assertEquals(
            listOf("Review changes", "Squash-merge into main…", "Merge into main…", "Rebase onto origin/main…"),
            labels(items).take(4),
        )
        items[3].onClick()
        assertEquals(listOf("/wt"), rebased)
    }

    @Test
    fun `offers no rebase for the base's local branch, but one without a local branch to merge into`() {
        val onTarget = worktree("/repo").copy(branch = "refs/heads/main", isMain = true)
        val forTarget = worktreeContextMenuItems(
            onTarget,
            {},
            {},
            {},
            baseBranch = "refs/remotes/origin/main",
            mergeTarget = "refs/heads/main",
        )
        val withoutTarget = worktreeContextMenuItems(
            worktree("/wt"),
            {},
            {},
            {},
            baseBranch = "refs/remotes/origin/main",
            mergeTarget = null,
        )

        assertFalse(labels(forTarget).any { it.startsWith("Rebase") }, labels(forTarget).toString())
        assertEquals(listOf("Review changes", "Rebase onto origin/main…"), labels(withoutTarget).take(2))
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
