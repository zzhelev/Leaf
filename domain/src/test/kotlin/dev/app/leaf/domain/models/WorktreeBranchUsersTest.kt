// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreeBranchUsersTest {
    @Test
    fun `a worktree uses the branch it has checked out`() {
        val worktree = worktree("/repo", branch = "refs/heads/main")

        assertEquals(WorktreeBranchUse.CheckedOut, worktree.useOf("refs/heads/main"))
        assertNull(worktree.useOf("refs/heads/feature"))
        assertEquals(listOf("refs/heads/main"), worktree.usedBranches)
    }

    @Test
    fun `a detached worktree uses the branch it rebases or bisects from`() {
        val rebasing = worktree("/rebasing", rebasingBranch = "refs/heads/feature")
        val bisecting = worktree("/bisecting", bisectingBranch = "refs/heads/feature")
        val detached = worktree("/detached")

        assertEquals(WorktreeBranchUse.Rebasing, rebasing.useOf("refs/heads/feature"))
        assertEquals(WorktreeBranchUse.Bisecting, bisecting.useOf("refs/heads/feature"))
        assertNull(detached.useOf("refs/heads/feature"))
        assertEquals(listOf("refs/heads/feature"), rebasing.usedBranches)
        assertEquals(listOf("refs/heads/feature"), bisecting.usedBranches)
        assertEquals(emptyList<String>(), detached.usedBranches)
    }

    @Test
    fun `a rebase comes before a bisect of the same branch, as in git`() {
        val worktree = worktree("/wt", rebasingBranch = "refs/heads/a", bisectingBranch = "refs/heads/a")

        assertEquals(WorktreeBranchUse.Rebasing, worktree.useOf("refs/heads/a"))
        assertEquals(listOf("refs/heads/a"), worktree.usedBranches)
    }

    @Test
    fun `a detached worktree can rebase one branch and bisect from another`() {
        val worktree = worktree("/wt", rebasingBranch = "refs/heads/a", bisectingBranch = "refs/heads/b")

        assertEquals(WorktreeBranchUse.Rebasing, worktree.useOf("refs/heads/a"))
        assertEquals(WorktreeBranchUse.Bisecting, worktree.useOf("refs/heads/b"))
        assertEquals(listOf("refs/heads/a", "refs/heads/b"), worktree.usedBranches)
    }

    @Test
    fun `lists the worktrees that use each branch, in the list's order`() {
        val main = info(worktree("/repo", branch = "refs/heads/main", isMain = true, isCurrent = true))
        val forced = info(worktree("/forced", branch = "refs/heads/main"))
        val rebasing = info(worktree("/rebasing", rebasingBranch = "refs/heads/feature"))
        val detached = info(worktree("/detached"))

        val users = WorktreeList("refs/heads/main", listOf(main, forced, rebasing, detached)).usersByBranch()

        assertEquals(
            mapOf(
                "refs/heads/main" to BranchWorktreeUsers(
                    listOf(
                        WorktreeBranchUser(main, WorktreeBranchUse.CheckedOut),
                        WorktreeBranchUser(forced, WorktreeBranchUse.CheckedOut),
                    )
                ),
                "refs/heads/feature" to BranchWorktreeUsers(
                    listOf(WorktreeBranchUser(rebasing, WorktreeBranchUse.Rebasing))
                ),
            ),
            users,
        )
    }

    @Test
    fun `a branch checked out in another worktree can't be checked out or deleted, but can be renamed`() {
        val other = info(worktree("/other", branch = "refs/heads/feature"))
        val users = BranchWorktreeUsers(listOf(WorktreeBranchUser(other, WorktreeBranchUse.CheckedOut)))

        assertEquals(WorktreeBranchUser(other, WorktreeBranchUse.CheckedOut), users.other)
        assertFalse(users.canCheckout)
        assertFalse(users.canDelete)
        assertTrue(users.canRename)
    }

    @Test
    fun `the tab's own branch isn't another worktree's, but can't be deleted`() {
        val current = info(worktree("/repo", branch = "refs/heads/main", isCurrent = true))
        val users = BranchWorktreeUsers(listOf(WorktreeBranchUser(current, WorktreeBranchUse.CheckedOut)))

        assertNull(users.other)
        assertTrue(users.canCheckout)
        assertFalse(users.canDelete)
        assertTrue(users.canRename)
    }

    @Test
    fun `names another worktree even when the tab's comes first`() {
        val current = info(worktree("/repo", branch = "refs/heads/main", isMain = true, isCurrent = true))
        val forced = info(worktree("/forced", branch = "refs/heads/main"))
        val users = BranchWorktreeUsers(
            listOf(
                WorktreeBranchUser(current, WorktreeBranchUse.CheckedOut),
                WorktreeBranchUser(forced, WorktreeBranchUse.CheckedOut),
            )
        )

        assertEquals(forced, users.other?.info)
        assertFalse(users.canCheckout)
    }

    @Test
    fun `a branch that a worktree rebases or bisects from can't be renamed, even by the tab's worktree`() {
        for (use in listOf(WorktreeBranchUse.Rebasing, WorktreeBranchUse.Bisecting)) {
            val current = info(worktree("/repo", isCurrent = true))
            val users = BranchWorktreeUsers(listOf(WorktreeBranchUser(current, use)))

            assertTrue(users.canCheckout, use.name)
            assertFalse(users.canDelete, use.name)
            assertFalse(users.canRename, use.name)
        }
    }

    @Test
    fun `a branch that no worktree uses has no limits`() {
        val users = BranchWorktreeUsers(emptyList())

        assertNull(users.other)
        assertTrue(users.canCheckout)
        assertTrue(users.canDelete)
        assertTrue(users.canRename)
    }

    private fun worktree(
        path: String,
        branch: String? = null,
        isMain: Boolean = false,
        isCurrent: Boolean = false,
        rebasingBranch: String? = null,
        bisectingBranch: String? = null,
    ) = Worktree(
        path = path,
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = branch,
        isMain = isMain,
        isCurrent = isCurrent,
        isDetached = branch == null,
        isBare = false,
        locked = null,
        prunable = null,
        rebasingBranch = rebasingBranch,
        bisectingBranch = bisectingBranch,
    )

    private fun info(worktree: Worktree) =
        WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = null)
}
