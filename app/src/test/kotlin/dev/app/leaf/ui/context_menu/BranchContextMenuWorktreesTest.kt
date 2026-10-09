// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeBranchUser
import dev.app.leaf.domain.models.WorktreeInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/** The branch menu leaves out what the worktree guards would refuse (fork-only). */
class BranchContextMenuWorktreesTest {
    private val main = Branch(HASH, "refs/heads/main", true)
    private val feature = Branch(HASH, "refs/heads/feature", true)

    @Test
    fun `offers everything for a branch that no worktree uses`() {
        val all = listOf(
            "Checkout branch",
            "Merge branch",
            "Rebase branch",
            "Rename branch",
            "Change default upstream branch",
            "Delete branch",
            "Copy branch name",
        )

        assertEquals(all, labels(worktreeUsers = null))
        assertEquals(all, labels(worktreeUsers = BranchWorktreeUsers(emptyList())))
    }

    @Test
    fun `leaves out checking out and deleting a branch that another worktree has checked out`() {
        assertEquals(
            listOf(
                "Merge branch",
                "Rebase branch",
                "Rename branch",
                "Change default upstream branch",
                "Copy branch name",
            ),
            labels(users(isCurrent = false, WorktreeBranchUse.CheckedOut)),
        )
    }

    @Test
    fun `also leaves out renaming a branch that another worktree rebases or bisects from`() {
        for (use in listOf(WorktreeBranchUse.Rebasing, WorktreeBranchUse.Bisecting)) {
            assertEquals(
                listOf("Merge branch", "Rebase branch", "Change default upstream branch", "Copy branch name"),
                labels(users(isCurrent = false, use)),
                use.name,
            )
        }
    }

    @Test
    fun `leaves out deleting and renaming a branch that the tab's worktree rebases`() {
        // The tab's worktree is detached while it rebases, so its current branch is HEAD
        val head = Branch(HASH, "HEAD", true)

        assertEquals(
            listOf("Checkout branch", "Change default upstream branch", "Copy branch name"),
            labels(users(isCurrent = true, WorktreeBranchUse.Rebasing), currentBranch = head),
        )
    }

    private fun users(isCurrent: Boolean, use: WorktreeBranchUse): BranchWorktreeUsers {
        val worktree = Worktree(
            path = "/wt",
            headSha = HASH,
            branch = if (use == WorktreeBranchUse.CheckedOut) feature.name else null,
            isMain = false,
            isCurrent = isCurrent,
            isDetached = use != WorktreeBranchUse.CheckedOut,
            isBare = false,
            locked = null,
            prunable = null,
        )
        val info = WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = null)

        return BranchWorktreeUsers(listOf(WorktreeBranchUser(info, use)))
    }

    /** The labels of the menu of the local branch `feature`, read in a composition as the menu reads them. */
    private fun labels(worktreeUsers: BranchWorktreeUsers?, currentBranch: Branch = main): List<String> {
        val items = branchContextMenuItems(
            branch = feature,
            isCurrentBranch = false,
            currentBranch = currentBranch,
            isLocal = true,
            onCheckoutBranch = {},
            onMergeBranch = {},
            onRebaseBranch = {},
            onDeleteBranch = {},
            onPushToRemoteBranch = {},
            onPullFromRemoteBranch = {},
            onChangeDefaultUpstreamBranch = {},
            onRenameBranch = {},
            onCopyBranchNameToClipboard = {},
            worktreeUsers = worktreeUsers,
        ).filterIsInstance<ContextMenuElement.ContextTextEntry>()

        var labels = emptyList<String>()
        val scene = ImageComposeScene(width = 10, height = 10) {
            labels = items.map { it.composableLabel() }
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        return labels
    }
}
