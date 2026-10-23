// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Local branches' copies on their remotes, which the dialog can delete with them (fork-only, follows upstream issue 116). */
class BranchCopiesTest {
    private val origin = DeletionRemote("origin", "git@example.com:team/app.git", lastFetchedMillis = null)

    private fun tip(name: String) = name.hashCode().toUInt().toString(16).padEnd(40, '0')

    private fun remoteInfo(name: String, merged: MergedIntoBase? = null, refusal: BranchKept? = null) =
        BranchDeletionInfo(BranchToDelete("refs/remotes/origin/$name", tip("origin/$name")), refusal, merged, 0)

    private fun localInfo(name: String, refusal: BranchKept? = null) = BranchDeletionInfo(
        BranchToDelete("refs/heads/$name", tip(name)),
        refusal,
        MergedIntoBase.COMMITS,
        0,
        upstream = BranchUpstream("origin", "refs/remotes/origin/$name"),
    )

    private val remotePlan = BranchDeletionPlan(
        "refs/remotes/origin/main",
        listOf(
            remoteInfo("merged", MergedIntoBase.COMMITS),
            remoteInfo("squashed", MergedIntoBase.CHANGES),
            remoteInfo("wip"),
            remoteInfo("develop", MergedIntoBase.COMMITS),
            remoteInfo("main", MergedIntoBase.COMMITS, BranchKept.RemoteDefaultBranch(isNamedByRemote = true)),
        ),
        remote = origin,
    )

    private val upstreams = listOf("merged", "squashed", "wip", "develop", "main", "used")
        .associate { "refs/heads/$it" to "refs/remotes/origin/$it" }

    private val copies = remotePlan.copiesFor(upstreams)

    @Test
    fun `a copy goes with its branch unless the server would refuse it, and the risky ones are marked`() {
        assertEquals(
            mapOf(
                "merged" to true,
                "squashed" to true,
                "wip" to true,
                "develop" to true,
                "main" to false,
            ),
            copies.entries.associate { (local, copy) -> local.removePrefix("refs/heads/") to copy.goes },
        )
        assertEquals(
            mapOf("merged" to false, "squashed" to false, "wip" to true, "develop" to true, "main" to false),
            copies.entries.associate { (local, copy) -> local.removePrefix("refs/heads/") to copy.isRisky },
        )
        assertTrue(copies.getValue("refs/heads/develop").looksLongLived)
        assertEquals(
            BranchDeletionKind.Refused(BranchKept.RemoteDefaultBranch(isNamedByRemote = true)),
            copies.getValue("refs/heads/main").kind,
        )
        assertEquals("origin", copies.getValue("refs/heads/merged").remote)
        // A remote-tracking branch that the remote's plan doesn't have isn't a copy
        assertFalse("refs/heads/used" in copies)
    }

    @Test
    fun `a plan that isn't a remote's says nothing about copies`() {
        assertTrue(remotePlan.copy(remote = null).copiesFor(upstreams).isEmpty())
    }

    @Test
    fun `only the checked branches that can be deleted take their copies, and only those that go`() {
        val used = BranchKept.UsedByWorktree("/w", WorktreeBranchUse.CheckedOut, isHere = false)
        val plan = BranchDeletionPlan(
            "refs/heads/main",
            listOf(localInfo("merged"), localInfo("squashed"), localInfo("wip"), localInfo("used", refusal = used)),
            copies = copies + ("refs/heads/used" to copies.getValue("refs/heads/merged")),
        )
        val all = setOf("refs/heads/merged", "refs/heads/squashed", "refs/heads/wip", "refs/heads/used")

        assertEquals(
            listOf("refs/heads/merged", "refs/heads/squashed", "refs/heads/wip"),
            plan.copiesToDelete(all).keys.toList(),
        )
        assertEquals(listOf("refs/heads/squashed"), plan.copiesToDelete(setOf("refs/heads/squashed")).keys.toList())
        assertTrue(plan.offersCopies)
    }

    @Test
    fun `no copy that can go, nothing offered`() {
        val plan = BranchDeletionPlan("refs/heads/main", listOf(localInfo("main")), copies = copies)

        assertFalse(plan.offersCopies)
        assertTrue(plan.copiesToDelete(setOf("refs/heads/main")).isEmpty())
    }

    @Test
    fun `deleting anyway takes the commit the server has now, for branches and copies`() {
        val now = "7".repeat(40)
        val pushed = BranchToDelete("refs/remotes/origin/pushed", tip("origin/pushed"))
        val unknown = BranchToDelete("refs/remotes/origin/unknown", tip("origin/unknown"))
        val copy = BranchToDelete("refs/remotes/origin/merged", tip("origin/merged"))
        val deletion = BranchesDeletion(
            outcomes = listOf(
                BranchDeletionOutcome.Kept(pushed, BranchKept.ChangedOnRemote(now)),
                BranchDeletionOutcome.Kept(unknown, BranchKept.ChangedOnRemote(now = null)),
            ),
            copies = mapOf("refs/heads/merged" to BranchDeletionOutcome.Kept(copy, BranchKept.ChangedOnRemote(now))),
        )

        val anyway = pushed.copy(serverTip = now)

        assertEquals(listOf(anyway), deletion.changedOnRemote)
        assertEquals(now, anyway.leaseTip)
        assertEquals(mapOf("refs/heads/merged" to ("origin" to copy.copy(serverTip = now))), deletion.changedCopies)

        val updated = deletion.withOutcomes(listOf(BranchDeletionOutcome.Deleted(anyway)), "remote said")

        assertEquals(BranchDeletionOutcome.Deleted(anyway), updated.outcomes.first())
        assertEquals(deletion.outcomes[1], updated.outcomes[1])
        assertEquals("remote said", updated.remoteMessage)
        assertTrue(updated.changedOnRemote.isEmpty())
    }

    @Test
    fun `a copy that goes with a branch that can't be deleted isn't offered`() {
        val used = BranchKept.UsedByWorktree("/w", WorktreeBranchUse.CheckedOut, isHere = true)
        val plan = BranchDeletionPlan("refs/heads/main", listOf(localInfo("merged", refusal = used)), copies = copies)

        assertFalse(plan.offersCopies)
    }

    @Test
    fun `the list of deleted branches ends with the deleted copies, named with their remote`() {
        val local = BranchToDelete("refs/heads/merged", tip("merged"))
        val copy = BranchToDelete("refs/remotes/origin/merged", tip("origin/merged"))
        val kept = BranchToDelete("refs/remotes/origin/squashed", tip("origin/squashed"))
        val deletion = BranchesDeletion(
            outcomes = listOf(BranchDeletionOutcome.Deleted(local)),
            copies = mapOf(
                "refs/heads/merged" to BranchDeletionOutcome.Deleted(copy),
                "refs/heads/other" to BranchDeletionOutcome.Deleted(copy),
                "refs/heads/squashed" to BranchDeletionOutcome.Kept(kept, BranchKept.ChangedOnRemote(now = null)),
            ),
        )

        assertEquals(listOf(copy), deletion.deletedCopies)
        assertEquals("merged ${tip("merged")}\norigin/merged ${tip("origin/merged")}", deletion.deletedList())
        assertEquals("origin/merged", copy.remoteAndName)
    }
}
