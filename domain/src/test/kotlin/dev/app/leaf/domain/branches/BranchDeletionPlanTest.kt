// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BranchDeletionPlanTest {
    private val main = "refs/heads/main"
    private val usedElsewhere = BranchKept.UsedByWorktree("/work/agent1", WorktreeBranchUse.CheckedOut, isHere = false)
    private val usedHere = BranchKept.UsedByWorktree("/work/leaf", WorktreeBranchUse.CheckedOut, isHere = true)

    private fun info(
        name: String,
        merged: MergedIntoBase? = null,
        only: Int = 0,
        refusal: BranchKept.UsedByWorktree? = null,
    ) = BranchDeletionInfo(
        branch = BranchToDelete("refs/heads/$name", name.padEnd(40, '0')),
        refusal = refusal,
        merged = merged,
        commitsOnlyHere = only,
    )

    private fun plan(vararg infos: BranchDeletionInfo, base: String? = main, mergedOnly: Boolean = false) =
        BranchDeletionPlan(base, infos.toList(), mergedOnly)

    @Test
    fun `a branch a worktree uses is refused, whatever else is known about it`() {
        val used = info("used", merged = MergedIntoBase.COMMITS, refusal = usedElsewhere)
        val here = info("here", only = 4, refusal = usedHere)
        val plan = plan(used, here)

        assertEquals(BranchDeletionKind.Refused(usedElsewhere), plan.kindOf(used))
        assertEquals(BranchDeletionKind.Refused(usedHere), plan.kindOf(here))
        assertFalse(plan.canCheck(used))
        assertFalse(plan.canCheck(here))
    }

    @Test
    fun `a branch in the base is merged, and so is a squash-merged one with its commits that go`() {
        val inBase = info("in-base", merged = MergedIntoBase.COMMITS)
        val squashed = info("squashed", merged = MergedIntoBase.CHANGES, only = 3)
        val plan = plan(inBase, squashed)

        assertEquals(BranchDeletionKind.Merged(MergedIntoBase.COMMITS, 0), plan.kindOf(inBase))
        assertEquals(BranchDeletionKind.Merged(MergedIntoBase.CHANGES, 3), plan.kindOf(squashed))
    }

    @Test
    fun `a branch that is not merged but has no commit of its own is on other refs`() {
        val plan = plan(info("pushed"))

        assertEquals(BranchDeletionKind.OnOtherRefs, plan.kindOf(plan.infos.single()))
    }

    @Test
    fun `a branch with commits of its own that nothing else has is unmerged`() {
        val plan = plan(info("feature", only = 5))

        assertEquals(BranchDeletionKind.Unmerged(5), plan.kindOf(plan.infos.single()))
    }

    @Test
    fun `the branches that lose nothing are checked at first and the others are not`() {
        val plan = plan(
            info("in-base", merged = MergedIntoBase.COMMITS),
            info("squashed", merged = MergedIntoBase.CHANGES, only = 2),
            info("pushed"),
            info("feature", only = 5),
            info("used", merged = MergedIntoBase.COMMITS, refusal = usedElsewhere),
        )

        assertEquals(
            setOf("refs/heads/in-base", "refs/heads/squashed", "refs/heads/pushed"),
            plan.checkedAtFirst(),
        )
    }

    @Test
    fun `when the base goes too nothing counts as merged into it`() {
        val baseInfo = info("main", merged = MergedIntoBase.COMMITS)
        val inBase = info("in-base", merged = MergedIntoBase.COMMITS, only = 2)
        val plan = plan(baseInfo, inBase)

        assertEquals(BranchDeletionKind.Unmerged(2), plan.kindOf(inBase), "Its commits would be on no branch")
        assertEquals(BranchDeletionKind.OnOtherRefs, plan.kindOf(baseInfo))
        assertEquals(setOf("refs/heads/main"), plan.checkedAtFirst())
    }

    @Test
    fun `a base that a worktree uses stays, so what is merged into it is still merged`() {
        val baseInfo = info("main", merged = MergedIntoBase.COMMITS, refusal = usedHere)
        val inBase = info("in-base", merged = MergedIntoBase.COMMITS)
        val plan = plan(baseInfo, inBase)

        assertEquals(BranchDeletionKind.Merged(MergedIntoBase.COMMITS, 0), plan.kindOf(inBase))
        assertEquals(setOf("refs/heads/in-base"), plan.checkedAtFirst())
    }

    @Test
    fun `a remote base is not among the branches, so what is merged into it stays merged`() {
        val plan = plan(info("in-base", merged = MergedIntoBase.COMMITS), base = "refs/remotes/origin/main")

        assertEquals(BranchDeletionKind.Merged(MergedIntoBase.COMMITS, 0), plan.kindOf(plan.infos.single()))
    }

    @Test
    fun `without a base nothing is merged`() {
        val plan = plan(info("feature", only = 1), info("pushed"), base = null)

        assertEquals(BranchDeletionKind.Unmerged(1), plan.kindOf(plan.infos[0]))
        assertEquals(BranchDeletionKind.OnOtherRefs, plan.kindOf(plan.infos[1]))
    }

    @Test
    fun `only safe kinds count as safe`() {
        assertTrue(BranchDeletionKind.Merged(MergedIntoBase.CHANGES, 9).isSafe)
        assertTrue(BranchDeletionKind.OnOtherRefs.isSafe)
        assertFalse(BranchDeletionKind.Unmerged(1).isSafe)
        assertFalse(BranchDeletionKind.Refused(usedElsewhere).isSafe)
    }

    @Test
    fun `all branches are listed unless only the merged ones are asked for`() {
        val infos = arrayOf(
            info("in-base", merged = MergedIntoBase.COMMITS),
            info("feature", only = 2),
            info("used", merged = MergedIntoBase.CHANGES, only = 1, refusal = usedElsewhere),
            info("pushed"),
        )

        assertEquals(4, plan(*infos).rows.size)
        assertEquals(
            listOf("refs/heads/in-base", "refs/heads/used"),
            plan(*infos, mergedOnly = true).rows.map { it.branch.name },
            "The merged ones, the one a worktree uses included, so that the user sees why it stays",
        )
    }

    @Test
    fun `what is deleted is what is checked, in the dialog's order, without refused branches`() {
        val plan = plan(
            info("a", merged = MergedIntoBase.COMMITS),
            info("b", only = 1),
            info("c", refusal = usedElsewhere),
            info("d"),
        )

        val toDelete = plan.toDelete(checked = setOf("refs/heads/d", "refs/heads/c", "refs/heads/a"))

        assertEquals(listOf("refs/heads/a", "refs/heads/d"), toDelete.map { it.name })
        assertEquals("a".padEnd(40, '0'), toDelete.first().tip)
    }

    @Test
    fun `an unchecked branch is not deleted and a name the plan does not list is ignored`() {
        val plan = plan(info("a"), info("b"))

        assertEquals(emptyList<BranchToDelete>(), plan.toDelete(emptySet()))
        assertEquals(listOf("refs/heads/b"), plan.toDelete(setOf("refs/heads/b", "refs/heads/other")).map { it.name })
    }

    @Test
    fun `a deletion lists what was deleted with its tip, and what stayed`() {
        val deleted = BranchToDelete("refs/heads/claude/x", "a1b2c3d4".padEnd(40, '0'))
        val kept = BranchToDelete("refs/heads/y", "e5f6a7b8".padEnd(40, '0'))
        val result = BranchesDeletion(
            listOf(
                BranchDeletionOutcome.Deleted(deleted),
                BranchDeletionOutcome.Kept(kept, BranchKept.NoLongerExists),
            ),
        )

        assertEquals(listOf(deleted), result.deleted)
        assertEquals(listOf(BranchDeletionOutcome.Kept(kept, BranchKept.NoLongerExists)), result.kept)
        assertEquals("claude/x ${"a1b2c3d4".padEnd(40, '0')}", result.deletedList())
        assertEquals("a1b2c3d", deleted.shortTip)
        assertEquals("claude/x", deleted.simpleName)
        assertEquals("e5f6a7b", BranchKept.Moved("e5f6a7b8".padEnd(40, '0')).shortNow)
    }
}
