// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.WorktreeBaseBranch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/** "New worktree…" in the menus of branches and commits (fork-only). */
class NewWorktreeMenusTest {
    private val main = Branch(HASH, "refs/heads/main", true)
    private val feature = Branch(HASH, "refs/heads/feature", true)
    private val remote = Branch(HASH, "refs/remotes/origin/feature", false)

    @Test
    fun `offers a new worktree from a branch, with the other worktree items, and opens it`() {
        var opened = 0
        val items = branchMenu(feature, onNewWorktree = { opened++ })
        val entries = items.filterIsInstance<ContextMenuElement.ContextTextEntry>()

        // Checkout, Merge, Rebase, Rename, Change upstream and Delete come first, Copy branch name last
        assertEquals(listOf("New worktree…", "Compare worktrees to this branch"), labels(items).subList(6, 8))
        assertEquals(ContextMenuElement.ContextSeparator, items[items.indexOf(entries[6]) - 1])
        assertEquals(ContextMenuElement.ContextSeparator, items[items.indexOf(entries[7]) + 1])

        entries[6].onClick()
        assertEquals(1, opened)
    }

    @Test
    fun `offers a new worktree from a remote branch too`() {
        assertEquals(true, "New worktree…" in labels(branchMenu(remote, onNewWorktree = {})))
    }

    @Test
    fun `offers no new worktree where nobody passed it`() {
        assertFalse("New worktree…" in labels(branchMenu(feature, onNewWorktree = null)))
    }

    @Test
    fun `offers a new worktree from a commit, after creating a branch`() {
        var opened = 0
        val items = logMenu(onNewWorktree = { opened++ })
        val entries = items.filterIsInstance<ContextMenuElement.ContextTextEntry>()
        val labels = labels(items)

        assertEquals(listOf("Checkout commit", "Create branch", "New worktree from commit…", "Create tag"), labels.take(4))

        entries[2].onClick()
        assertEquals(1, opened)
        assertFalse("New worktree from commit…" in labels(logMenu(onNewWorktree = null)))
    }

    private fun branchMenu(branch: Branch, onNewWorktree: (() -> Unit)?) = branchContextMenuItems(
        branch = branch,
        isCurrentBranch = false,
        currentBranch = main,
        isLocal = branch.isLocal,
        onCheckoutBranch = {},
        onMergeBranch = {},
        onRebaseBranch = {},
        onDeleteBranch = {},
        onPushToRemoteBranch = {},
        onPullFromRemoteBranch = {},
        onChangeDefaultUpstreamBranch = {},
        onRenameBranch = {},
        onCopyBranchNameToClipboard = {},
        worktreesBase = WorktreeBaseBranch(automatic = "refs/heads/main"),
        onNewWorktree = onNewWorktree,
    )

    private fun logMenu(onNewWorktree: (() -> Unit)?) = logContextMenu(
        onShowStatusAmending = {},
        onCheckoutCommit = {},
        onCreateNewBranch = {},
        onCreateNewTag = {},
        onRevertCommit = {},
        onCherryPickCommit = {},
        onResetBranch = {},
        onCopyCommitHash = {},
        showInAmend = false,
        onNewWorktree = onNewWorktree,
    )

    /** The labels of the text entries of [items], read in a composition as the menu reads them. */
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
