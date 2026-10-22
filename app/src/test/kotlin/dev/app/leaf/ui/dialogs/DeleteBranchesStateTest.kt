// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.branches.DeletionRemote
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What checking branches in the "Delete branches" dialog does to its state (fork-only, upstream issue 116). */
class DeleteBranchesStateTest {
    private fun info(
        name: String,
        merged: MergedIntoBase? = null,
        only: Int = 0,
        refusal: BranchKept.UsedByWorktree? = null,
    ) = BranchDeletionInfo(BranchToDelete("refs/heads/$name", name.padEnd(40, '0')), refusal, merged, only)

    private val used = BranchKept.UsedByWorktree("/work/agent1", WorktreeBranchUse.CheckedOut, isHere = false)
    private val plan = BranchDeletionPlan(
        base = "refs/heads/main",
        infos = listOf(
            info("merged", merged = MergedIntoBase.COMMITS),
            info("unmerged", only = 3),
            info("pushed"),
            info("used", merged = MergedIntoBase.COMMITS, refusal = used),
        ),
    )
    private val request = BranchesDeletionRequest(emptyList())
    private val ready = DeleteBranchesState(request, plan = plan, checked = plan.checkedAtFirst())

    @Test
    fun `starts with the branches that lose nothing, in the dialog's order`() {
        assertEquals(setOf("refs/heads/merged", "refs/heads/pushed"), ready.checked)
        assertEquals(listOf("merged", "pushed"), ready.toDelete.map { it.simpleName })
        assertTrue(ready.canConfirm)
    }

    @Test
    fun `checks and unchecks a branch`() {
        val checked = ready.withChecked("refs/heads/unmerged", true)

        assertEquals(listOf("merged", "unmerged", "pushed"), checked.toDelete.map { it.simpleName })
        assertEquals(listOf("pushed"), checked.withChecked("refs/heads/unmerged", false).withChecked("refs/heads/merged", false)
            .toDelete.map { it.simpleName })
    }

    @Test
    fun `a branch that a worktree uses can't be checked, nor a name the list doesn't have`() {
        assertSame(ready, ready.withChecked("refs/heads/used", true))
        assertSame(ready, ready.withChecked("refs/heads/missing", true))
        assertFalse("refs/heads/used" in ready.withAllChecked().checked)
    }

    @Test
    fun `all checks every branch that can be deleted, safe the ones that lose nothing, none nothing`() {
        assertEquals(
            setOf("refs/heads/merged", "refs/heads/unmerged", "refs/heads/pushed"),
            ready.withAllChecked().checked,
        )
        assertEquals(setOf("refs/heads/merged", "refs/heads/pushed"), ready.withAllChecked().withSafeChecked().checked)
        assertEquals(emptySet<String>(), ready.withNoneChecked().checked)
        assertFalse(ready.withNoneChecked().canConfirm, "Nothing checked, nothing to delete")
    }

    @Test
    fun `nothing changes while it deletes, or once the result is shown`() {
        val deleting = ready.copy(isDeleting = true)
        val done = ready.copy(result = BranchesDeletion(emptyList()))

        for (locked in listOf(deleting, done)) {
            assertSame(locked, locked.withChecked("refs/heads/unmerged", true))
            assertSame(locked, locked.withAllChecked())
            assertSame(locked, locked.withNoneChecked())
            assertSame(locked, locked.withSafeChecked())
            assertFalse(locked.canConfirm)
        }
    }

    @Test
    fun `without a plan there is nothing to check or confirm`() {
        val loading = DeleteBranchesState(request)

        assertSame(loading, loading.withChecked("refs/heads/merged", true))
        assertSame(loading, loading.withAllChecked())
        assertFalse(loading.canConfirm)
        assertEquals(emptyList<BranchToDelete>(), loading.toDelete)
    }

    @Test
    fun `a branch checked but not listed is never deleted`() {
        val state = ready.copy(checked = ready.checked + "refs/heads/not-in-the-list" + "refs/heads/used")

        assertEquals(listOf("merged", "pushed"), state.toDelete.map { it.simpleName })
    }

    @Test
    fun `the result keeps what happened to each branch`() {
        val outcome = BranchDeletionOutcome.Deleted(plan.infos.first().branch)
        val state = ready.copy(result = BranchesDeletion(listOf(outcome)))

        assertEquals(listOf(outcome), state.result?.outcomes)
    }

    // A remote's branches (follows upstream issue 116)

    private fun remoteInfo(name: String, merged: MergedIntoBase? = null, only: Int = 0) =
        BranchDeletionInfo(BranchToDelete("refs/remotes/origin/$name", name.padEnd(40, '0')), null, merged, only)

    private val remotePlan = BranchDeletionPlan(
        base = "refs/remotes/origin/main",
        infos = listOf(
            remoteInfo("done", merged = MergedIntoBase.COMMITS),
            remoteInfo("develop", merged = MergedIntoBase.COMMITS),
            remoteInfo("wip", only = 2),
            remoteInfo("pushed"),
        ),
        remote = DeletionRemote("origin", url = null, lastFetchedMillis = null),
    )
    private val remoteReady = DeleteBranchesState(
        BranchesDeletionRequest(emptyList(), remote = "origin"),
        plan = remotePlan,
        checked = remotePlan.checkedAtFirst(),
    )

    @Test
    fun `safe leaves a remote's branches that look long-lived unchecked, as the dialog starts`() {
        assertEquals(setOf("refs/remotes/origin/done"), remoteReady.checked)

        val all = remoteReady.withAllChecked()

        assertEquals(4, all.checked.size)
        assertEquals(setOf("refs/remotes/origin/done"), all.withSafeChecked().checked)
    }

    @Test
    fun `it knows when an unmerged branch is checked`() {
        assertFalse(remoteReady.checksUnmerged)
        assertTrue(remoteReady.withChecked("refs/remotes/origin/wip", true).checksUnmerged)
        assertFalse(remoteReady.withChecked("refs/remotes/origin/develop", true).checksUnmerged, "Merged, only long-lived")
        assertTrue(remoteReady.withChecked("refs/remotes/origin/pushed", true).checksUnmerged, "Its commits are elsewhere")
    }

    @Test
    fun `after a stop, nothing can be checked or deleted`() {
        val stopped = remoteReady.copy(wasStopped = true)

        assertFalse(stopped.canConfirm)
        assertSame(stopped, stopped.withChecked("refs/remotes/origin/wip", true))
        assertSame(stopped, stopped.withNoneChecked())
    }
}
