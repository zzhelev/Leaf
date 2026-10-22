// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.models.MergedIntoBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the dialog says about a remote's branches, and what it checks at first (fork-only, follows upstream issue 116). */
class RemoteBranchDeletionPlanTest {
    private val origin = DeletionRemote("origin", "git@example.com:team/app.git", lastFetchedMillis = 1_000L)

    private fun info(name: String, merged: MergedIntoBase? = null, only: Int = 0, refusal: BranchKept? = null) =
        BranchDeletionInfo(
            branch = BranchToDelete("refs/remotes/origin/$name", name.hashCode().toUInt().toString(16).padEnd(40, '0')),
            refusal = refusal,
            merged = merged,
            commitsOnlyHere = only,
            lastCommit = TipCommit("Alex", 0L),
        )

    private fun plan(vararg infos: BranchDeletionInfo, mergedOnly: Boolean = false) =
        BranchDeletionPlan("refs/remotes/origin/main", infos.toList(), mergedOnly, origin)

    @Test
    fun `a remote's branch is named without the remote, as on the server`() {
        assertEquals("feature/x", BranchToDelete("refs/remotes/origin/feature/x", "a".repeat(40)).simpleName)
        assertEquals("x", BranchToDelete("refs/remotes/upstream/x", "a".repeat(40)).simpleName)
        assertEquals("feature/x", BranchToDelete("refs/heads/feature/x", "a".repeat(40)).simpleName)
    }

    @Test
    fun `the list of deleted branches has a remote's by their names on the server`() {
        val tip = "b".repeat(40)
        val deletion = BranchesDeletion(listOf(BranchDeletionOutcome.Deleted(BranchToDelete("refs/remotes/origin/claude/x", tip))))

        assertEquals("claude/x $tip", deletion.deletedList())
    }

    @Test
    fun `the remote's HEAD and default branch are refused`() {
        val head = info("HEAD", merged = MergedIntoBase.COMMITS, refusal = BranchKept.RemoteHead)
        val main = info("main", merged = MergedIntoBase.COMMITS, refusal = BranchKept.RemoteDefaultBranch(true))
        val plan = plan(head, main, info("done", merged = MergedIntoBase.COMMITS))

        assertEquals(BranchDeletionKind.Refused(BranchKept.RemoteHead), plan.kindOf(head))
        assertEquals(BranchDeletionKind.Refused(BranchKept.RemoteDefaultBranch(true)), plan.kindOf(main))
        assertFalse(plan.canCheck(head))
        assertFalse(plan.canCheck(main))
        assertEquals(setOf("refs/remotes/origin/done"), plan.checkedAtFirst())
    }

    @Test
    fun `merged branches whose names look long-lived start unchecked, and can still be checked`() {
        val develop = info("develop", merged = MergedIntoBase.COMMITS)
        val release = info("release/2.0", merged = MergedIntoBase.COMMITS)
        val feature = info("claude/eager-ride", merged = MergedIntoBase.CHANGES)
        val plan = plan(develop, release, feature)

        assertTrue(plan.looksLongLived(develop))
        assertTrue(plan.looksLongLived(release))
        assertFalse(plan.looksLongLived(feature))
        assertEquals(setOf("refs/remotes/origin/claude/eager-ride"), plan.checkedAtFirst())
        assertTrue(plan.canCheck(develop))
        assertFalse(plan.isCheckedAtFirst(release))
    }

    @Test
    fun `local branches are never long-lived, the rule is for a remote's`() {
        val develop = BranchDeletionInfo(
            BranchToDelete("refs/heads/develop", "c".repeat(40)),
            refusal = null,
            merged = MergedIntoBase.COMMITS,
            commitsOnlyHere = 0,
        )
        val plan = BranchDeletionPlan("refs/heads/main", listOf(develop))

        assertFalse(plan.looksLongLived(develop))
        assertEquals(setOf("refs/heads/develop"), plan.checkedAtFirst())
    }

    @Test
    fun `a remote's branch that isn't merged starts unchecked, also with its commits on other branches`() {
        val unmerged = info("wip", only = 3)
        val pushed = info("pushed")
        val plan = plan(unmerged, pushed)

        assertEquals(BranchDeletionKind.Unmerged(3), plan.kindOf(unmerged))
        // Its commits may be on the user's own branch, and its pull request still open on the server
        assertEquals(BranchDeletionKind.OnOtherRefs, plan.kindOf(pushed))
        assertEquals(emptySet<String>(), plan.checkedAtFirst())
        assertTrue(plan.canCheck(pushed))
    }

    @Test
    fun `a local branch with its commits on other branches still starts checked`() {
        val pushed = BranchDeletionInfo(BranchToDelete("refs/heads/pushed", "d".repeat(40)), null, null, 0)

        assertEquals(setOf("refs/heads/pushed"), BranchDeletionPlan("refs/heads/main", listOf(pushed)).checkedAtFirst())
    }

    @Test
    fun `names that look long-lived, without case`() {
        for (name in listOf(
            "main", "master", "trunk", "develop", "Development", "dev", "next", "stable", "staging", "stage",
            "production", "prod", "live", "qa", "UAT", "gh-pages", "release/2.0", "Release/1", "releases/x",
            "release-1.4", "hotfix/crash", "support/3.x", "maintenance/2024",
        )) {
            assertTrue(looksLongLived(name), name)
        }

        for (name in listOf(
            "feature/x", "claude/silly-wiles", "developer-notes", "devtools", "mainline-fix", "my-release",
            "release", "releasenotes", "fix/production-bug", "qa-fixes", "prod2",
        )) {
            assertFalse(looksLongLived(name), name)
        }
    }
}
