// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.PlanAction.DROP
import dev.app.leaf.domain.rewrite.PlanAction.EDIT
import dev.app.leaf.domain.rewrite.PlanAction.FIXUP
import dev.app.leaf.domain.rewrite.PlanAction.SQUASH
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanFooterTest {
    private val someone = Identity("Author", "a@example.invalid")

    private fun commit(name: String, message: String, vararg parents: String) =
        Commit(name, message, someone, someone, 0, parents.toList().ifEmpty { listOf("base") })

    // feat, oldest first: a, b, c, d, e (tip). a, b and c are on origin/feat.
    private val a = commit("a", "Add the parser\n", "base")
    private val b = commit("b", "wip\n", "a")
    private val c = commit("c", "Cache the parsed files\n", "b")
    private val d = commit("d", "Test the cache\n", "c")
    private val e = commit("e", "Fix a typo\n", "d")
    private val feat = BranchLine(
        branch = "refs/heads/feat",
        base = "refs/heads/main",
        commits = listOf(e, d, c, b, a),
        upstream = "refs/remotes/origin/feat",
        pushed = setOf("a", "b", "c"),
    )
    private val plan = RebasePlan.of(feat, listOf(a, b, c, d, e))
    private val here = PlanPlace(worktreeName = "leaf", isTabs = true)
    private val noStop = PlanCheck(emptyList(), null)

    private fun checked(vararg groups: CheckedGroup, stop: PlanStop? = null, inTheWay: FilesInTheWay? = null) =
        PlanCheck(groups.toList(), stop, inTheWay)

    @Test
    fun `the counts say what the plan does, and the commits that come out empty aren't counted after`() {
        val changed = plan.withAction("b", SQUASH).withAction("d", DROP).moved(4, 2)
        val check = checked(
            CheckedGroup("a", listOf("a", "b"), "t1"),
            CheckedGroup("e", listOf("e"), "t2", emptyCommits = setOf("e")),
            CheckedGroup("c", listOf("c"), "t3"),
        )
        val footer = planFooter(changed, check, here, autostash = false)

        assertEquals(5, footer.before)
        assertEquals(2, footer.after)
        assertEquals(1, footer.joined)
        assertEquals(1, footer.dropped)
        assertTrue(footer.isReordered)
        assertEquals(0, footer.edits)
        assertEquals(PlanStart.REWRITE, footer.start)
    }

    @Test
    fun `Start waits for the check, and for a plan that changes something`() {
        val changed = plan.withAction("e", DROP)

        assertFalse(planFooter(changed, null, here, autostash = false).isChecked)
        assertFalse(planFooter(changed, null, here, autostash = false).canStart)
        assertTrue(planFooter(changed, noStop, here, autostash = false).canStart)
        assertFalse(planFooter(plan, noStop, here, autostash = false).canStart)
    }

    @Test
    fun `a conflict names its commit, its first file and how many more, and the plan starts a rebase`() {
        val changed = plan.withAction("b", DROP)
        val check = checked(stop = PlanStop.Conflict("c", listOf("src/Cache.kt", "src/CacheTest.kt", "README.md")))
        val footer = planFooter(changed, check, here, autostash = false)

        assertEquals(PlanNote.StopsAtConflict(c, "src/Cache.kt", 2), footer.notes.first())
        assertEquals(PlanStart.REBASE, footer.start)
        assertTrue(footer.canStart)
    }

    @Test
    fun `an edit and a plan that Leaf can't check both start a rebase`() {
        val edit = planFooter(plan.withAction("c", EDIT), checked(stop = PlanStop.Edit("c")), here, autostash = false)
        val unknown = planFooter(
            plan.withAction("c", DROP),
            checked(stop = PlanStop.Unknown(UnknownStopReason.OLD_GIT)),
            here,
            autostash = false,
        )

        assertEquals(listOf(PlanNote.StopsToEdit(c)), edit.notes.filterNot { it is PlanNote.Pushed })
        assertEquals(PlanStart.REBASE, edit.start)
        assertEquals(1, edit.edits)
        assertEquals(PlanNote.MayStop(UnknownStopReason.OLD_GIT), unknown.notes.first())
        assertEquals(PlanStart.REBASE, unknown.start)
    }

    @Test
    fun `a plan that stops says where it replays, and another worktree's changes keep Start disabled`() {
        val changed = plan.withAction("e", EDIT)
        val stop = checked(stop = PlanStop.Edit("e"))
        val there = PlanPlace(worktreeName = "leaf-feat", isTabs = false, uncommitted = 2)
        val nowhere = PlanPlace(worktreeName = null, isTabs = true)

        val inOther = planFooter(changed, stop, there, autostash = true)
        val checksOut = planFooter(changed, stop, nowhere, autostash = false)

        assertTrue(PlanNote.RunsIn("leaf-feat") in inOther.notes)
        assertTrue(PlanNote.ChangesThere("leaf-feat", 2) in inOther.notes)
        assertNull(inOther.autostashCount)
        assertFalse(inOther.canStart)
        assertTrue(PlanNote.ChecksOutHere("feat") in checksOut.notes)
        assertTrue(checksOut.canStart)
    }

    @Test
    fun `uncommitted changes in the tab's worktree are set aside only with the box, for a plan that stops`() {
        val changed = plan.withAction("e", EDIT)
        val stop = checked(stop = PlanStop.Edit("e"))
        val busy = here.copy(uncommitted = 3)

        assertEquals(3, planFooter(changed, stop, busy, autostash = false).autostashCount)
        assertFalse(planFooter(changed, stop, busy, autostash = false).canStart)
        assertTrue(planFooter(changed, stop, busy, autostash = true).canStart)

        // A plan that doesn't stop keeps them, unless its files meet them
        val rewrite = planFooter(plan.withAction("e", DROP), noStop, busy, autostash = false)

        assertNull(rewrite.autostashCount)
        assertTrue(rewrite.canStart)
    }

    @Test
    fun `files in the way keep a plan that doesn't stop from starting`() {
        val inTheWay = FilesInTheWay("/repo", listOf("NOTES.md"))
        val footer = planFooter(plan.withAction("e", DROP), checked(inTheWay = inTheWay), here, autostash = false)

        assertTrue(PlanNote.FilesInTheWay("/repo", listOf("NOTES.md")) in footer.notes)
        assertFalse(footer.canStart)
    }

    @Test
    fun `pushed commits that the plan rewrites are named with the upstream, and the ones it keeps aren't`() {
        assertTrue(PlanNote.Pushed(2, "origin/feat") in planFooter(plan.withAction("b", DROP), noStop, here, false).notes)
        assertTrue(planFooter(plan.withAction("e", DROP), noStop, here, false).notes.none { it is PlanNote.Pushed })
    }

    @Test
    fun `a branch that moved, an empty message and a refusal each keep Start disabled`() {
        val changed = plan.withAction("e", DROP)
        val moved = planFooter(changed, noStop, here, autostash = false, currentTip = "f")
        val empty = planFooter(plan.withMessage("e", "  \n"), noStop, here, autostash = false)

        assertTrue(PlanNote.BranchMoved in moved.notes)
        assertFalse(moved.canStart)
        assertTrue(PlanNote.EmptyMessage in empty.notes)
        assertFalse(empty.canStart)

        val merge = commit("m", "Merge main\n", "d", "x")
        val mergeLine = feat.copy(commits = listOf(e.copy(parentsHashes = listOf("m")), merge, d, c, b, a))
        val mergePlan = RebasePlan.of(mergeLine, mergeLine.commits.reversed()).withAction("a", DROP)
        val refused = planFooter(mergePlan, noStop, here, autostash = false)

        assertTrue(refused.notes.any { it is PlanNote.Refused })
        assertFalse(refused.canStart)
    }

    @Test
    fun `another worktree's recent change is said for any plan that moves its branch`() {
        val there = PlanPlace(worktreeName = "leaf-feat", isTabs = false, recentChange = 1_000)

        assertTrue(PlanNote.RecentChange("leaf-feat", 1_000) in planFooter(plan.withAction("e", DROP), noStop, there, false).notes)
        assertTrue(planFooter(plan, noStop, there, false).notes.none { it is PlanNote.RecentChange })
    }

    @Test
    fun `rows show what moved, what joins them, the stop's files, what comes out empty and what is pushed`() {
        val changed = plan.moved(4, 1).withAction("c", FIXUP)
        val check = checked(
            CheckedGroup("a", listOf("a"), "t1"),
            CheckedGroup("e", listOf("e"), "t2", emptyCommits = setOf("e")),
            stop = PlanStop.Conflict("b", listOf("x.kt")),
        )
        val marks = planRowMarks(changed, check)

        assertTrue(marks.getValue("e").isMoved)
        assertFalse(marks.getValue("a").isMoved)
        assertFalse(marks.getValue("d").isMoved)
        assertEquals(1, marks.getValue("b").joinedBy)
        assertEquals(listOf("x.kt"), marks.getValue("b").stopFiles)
        assertTrue(marks.getValue("e").isEmpty)
        assertTrue(marks.getValue("a").isPushed)
        assertFalse(marks.getValue("d").isPushed)
    }

    @Test
    fun `a row set to join with nothing below it shows that it stays a commit`() {
        // Joined first, then the row below it dropped
        val changed = plan.withAction("b", SQUASH).withAction("a", DROP)

        assertTrue(planRowMarks(changed, null).getValue("b").isPickedAlone)
        assertFalse(planRowMarks(changed, null).getValue("a").isMoved)
    }
}
