// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dev.app.leaf.domain.models.BranchMerged
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRemoval
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.worktrees.RemoveWorktreeOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the remove and discard worktree dialogs let the user do, for a worktree in each state. */
class RemoveWorktreeStateTest {
    private val clean = WorktreeStatus(0, 0, 0, 0, upstream = null, upstreamAheadBehind = null)
    private val dirty = clean.copy(unstaged = 2, untracked = 1)
    private val merged = BranchMerged(isMerged = true, commitsNotInBase = 0)
    private val unmerged = BranchMerged(isMerged = false, commitsNotInBase = 3)

    @Test
    fun `removes a clean worktree right away, and keeps its branch unless asked`() {
        val state = state(clean, merged)

        assertEquals(RemoveWorktreeOptions(), state.options)
        assertTrue(state.canConfirm)
        assertFalse(state.losesWork)
        assertEquals(ConfirmOutcome.RUN, state.onConfirm(discard = false))
    }

    @Test
    fun `removes a worktree with changes only once forced`() {
        val state = state(dirty, merged)

        assertFalse(state.canConfirm)
        assertEquals(ConfirmOutcome.NOTHING, state.onConfirm(discard = false))

        val forced = state.copy(options = state.options.copy(force = true))
        assertEquals(ConfirmOutcome.RUN, forced.onConfirm(discard = false))
    }

    @Test
    fun `takes changes that git couldn't read to be there`() {
        val state = state(status = null, merged)

        assertFalse(state.canConfirm)
        assertTrue(state.copy(options = RemoveWorktreeOptions(force = true)).canConfirm)
    }

    @Test
    fun `removes a locked worktree only once the user overrides the lock`() {
        val state = state(clean, merged, worktree = agent.copy(locked = ""))

        assertFalse(state.canConfirm)
        assertTrue(state.copy(options = RemoveWorktreeOptions(overrideLock = true)).canConfirm)
    }

    @Test
    fun `discards with force and the branch, asking twice when that loses work`() {
        val state = state(dirty, unmerged, discard = true)

        assertEquals(RemoveWorktreeOptions(force = true, deleteBranch = true), state.options)
        assertTrue(state.losesWork)
        assertEquals(ConfirmOutcome.ASK_AGAIN, state.onConfirm(discard = true))
        assertEquals(ConfirmOutcome.RUN, state.copy(confirmingLoss = true).onConfirm(discard = true))
    }

    @Test
    fun `discards a clean, merged worktree with one confirmation`() {
        val state = state(clean, merged, discard = true)

        assertFalse(state.losesWork)
        assertEquals(ConfirmOutcome.RUN, state.onConfirm(discard = true))
    }

    @Test
    fun `counts deleting an unmerged branch as lost work, and a squash-merged one as not`() {
        val squashMerged = BranchMerged(isMerged = true, commitsNotInBase = 2)

        assertTrue(state(clean, unmerged, discard = true).losesWork)
        assertTrue(state(clean, branchMerged = null, discard = true).losesWork)
        assertFalse(state(clean, squashMerged, discard = true).losesWork)
        assertFalse(state(clean, unmerged).losesWork, "removing keeps the branch unless asked")
    }

    @Test
    fun `discards a detached worktree without deleting a branch`() {
        val state = state(clean, branchMerged = null, discard = true, branch = null)

        assertEquals(RemoveWorktreeOptions(force = true, deleteBranch = false), state.options)
        assertFalse(state.losesWork)
    }

    @Test
    fun `can't confirm before the worktree was checked`() {
        assertEquals(ConfirmOutcome.NOTHING, RemoveWorktreeState().onConfirm(discard = true))
        assertTrue(RemoveWorktreeState().steps.isEmpty())
    }

    private val main = worktree("/dev/leaf", isMain = true)
    private val agent = worktree("/dev/leaf-agent1")

    private fun state(
        status: WorktreeStatus?,
        branchMerged: BranchMerged?,
        discard: Boolean = false,
        worktree: Worktree = agent,
        branch: String? = "agent1",
    ): RemoveWorktreeState {
        val removal = WorktreeRemoval(
            worktree = worktree,
            mainWorktree = main,
            status = status,
            lastChange = null,
            branch = branch,
            baseBranch = "refs/heads/main",
            branchMerged = branchMerged,
        )

        return RemoveWorktreeState(removal = removal, options = initialRemoveOptions(removal, discard))
    }

    private fun worktree(path: String, isMain: Boolean = false) = Worktree(
        path = path,
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = if (isMain) "refs/heads/main" else "refs/heads/agent1",
        isMain = isMain,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )
}
