// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
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
    fun `offers to switch to the worktree that has the branch, in place of checking it out and deleting it`() {
        assertEquals(
            listOf(
                "Switch to worktree",
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
                listOf(
                    "Switch to worktree",
                    "Merge branch",
                    "Rebase branch",
                    "Change default upstream branch",
                    "Copy branch name",
                ),
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

    @Test
    fun `switches to the worktree that has the branch`() {
        val users = users(isCurrent = false, WorktreeBranchUse.CheckedOut)
        val switchedTo = mutableListOf<Worktree>()

        // The first item, as the labels show
        menu(feature, main, users, worktreesBase = null, onSwitchToWorktree = { switchedTo += it })
            .first()
            .onClick()

        assertEquals(listOf(users.users.single().info.worktree), switchedTo)
    }

    @Test
    fun `offers no switch to a worktree whose folder is missing, nor a checkout`() {
        val prunable = "gitdir file points to non-existent location"

        assertEquals(
            listOf(
                "Merge branch",
                "Rebase branch",
                "Rename branch",
                "Change default upstream branch",
                "Copy branch name",
            ),
            labels(users(isCurrent = false, WorktreeBranchUse.CheckedOut, prunable)),
        )
    }

    @Test
    fun `offers to compare the worktrees to a branch that isn't their base`() {
        val base = WorktreeBaseBranch(automatic = main.name)

        assertEquals(
            listOf(
                "Checkout branch",
                "Merge branch",
                "Rebase branch",
                "Rename branch",
                "Change default upstream branch",
                "Delete branch",
                "Compare worktrees to this branch",
                "Copy branch name",
            ),
            labels(worktreeUsers = null, worktreesBase = base),
        )
        assertEquals(listOf("refs/heads/feature"), chosenBases(feature, base))

        // A branch that is chosen but missing isn't the base: another branch can still be chosen
        val missing = base.copy(chosen = "refs/heads/release", chosenExists = false)
        assertEquals(listOf("refs/heads/feature"), chosenBases(feature, missing))
    }

    @Test
    fun `offers to go back to automatic on the chosen branch`() {
        val base = WorktreeBaseBranch(automatic = main.name, chosen = feature.name, chosenExists = true)

        assertEquals(
            listOf(
                "Checkout branch",
                "Merge branch",
                "Rebase branch",
                "Rename branch",
                "Change default upstream branch",
                "Delete branch",
                "Compare worktrees automatically",
                "Copy branch name",
            ),
            labels(worktreeUsers = null, worktreesBase = base),
        )
        assertEquals(listOf(null), chosenBases(feature, base))
    }

    @Test
    fun `offers nothing on the automatic base, or without a base to choose`() {
        val automatic = WorktreeBaseBranch(automatic = feature.name)
        val withoutBase = labels(worktreeUsers = null, worktreesBase = null)

        assertEquals(withoutBase, labels(worktreeUsers = null, worktreesBase = automatic))
        assertEquals(emptyList<String?>(), chosenBases(feature, automatic))
        assertEquals(emptyList<String?>(), chosenBases(feature, null))
    }

    @Test
    fun `offers a remote branch as the base`() {
        val remote = Branch(HASH, "refs/remotes/origin/main", false)

        assertEquals(listOf("refs/remotes/origin/main"), chosenBases(remote, WorktreeBaseBranch(automatic = main.name)))

        val chosen = WorktreeBaseBranch(automatic = main.name, chosen = remote.name, chosenExists = true)
        assertEquals(listOf(null), chosenBases(remote, chosen))
    }

    private fun users(isCurrent: Boolean, use: WorktreeBranchUse, prunable: String? = null): BranchWorktreeUsers {
        val worktree = Worktree(
            path = "/wt",
            headSha = HASH,
            branch = if (use == WorktreeBranchUse.CheckedOut) feature.name else null,
            isMain = false,
            isCurrent = isCurrent,
            isDetached = use != WorktreeBranchUse.CheckedOut,
            isBare = false,
            locked = null,
            prunable = prunable,
        )
        val info = WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = null)

        return BranchWorktreeUsers(listOf(WorktreeBranchUser(info, use)))
    }

    /** The labels of the menu of the local branch `feature`, read in a composition as the menu reads them. */
    private fun labels(
        worktreeUsers: BranchWorktreeUsers?,
        currentBranch: Branch = main,
        worktreesBase: WorktreeBaseBranch? = null,
    ): List<String> {
        val items = menu(feature, currentBranch, worktreeUsers, worktreesBase)

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

    /**
     * The bases that [branch]'s menu chooses when each of its items is clicked (null for automatic). Only the compare
     * item chooses one, so the list is empty when the menu has none.
     */
    private fun chosenBases(branch: Branch, worktreesBase: WorktreeBaseBranch?): List<String?> {
        val chosen = mutableListOf<String?>()

        menu(branch, main, worktreeUsers = null, worktreesBase, onChooseWorktreesBase = { chosen += it })
            .forEach { it.onClick() }

        return chosen
    }

    /** The menu of [branch], whose actions do nothing except choosing the worktrees' base. */
    private fun menu(
        branch: Branch,
        currentBranch: Branch,
        worktreeUsers: BranchWorktreeUsers?,
        worktreesBase: WorktreeBaseBranch?,
        onChooseWorktreesBase: (String?) -> Unit = {},
        onSwitchToWorktree: (Worktree) -> Unit = {},
    ): List<ContextMenuElement.ContextTextEntry> = branchContextMenuItems(
        branch = branch,
        isCurrentBranch = false,
        currentBranch = currentBranch,
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
        worktreeUsers = worktreeUsers,
        worktreesBase = worktreesBase,
        onChooseWorktreesBase = onChooseWorktreesBase,
        onSwitchToWorktree = onSwitchToWorktree,
    ).filterIsInstance<ContextMenuElement.ContextTextEntry>()
}
