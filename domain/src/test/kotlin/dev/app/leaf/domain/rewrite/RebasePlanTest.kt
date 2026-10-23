// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.PlanAction.DROP
import dev.app.leaf.domain.rewrite.PlanAction.EDIT
import dev.app.leaf.domain.rewrite.PlanAction.FIXUP
import dev.app.leaf.domain.rewrite.PlanAction.PICK
import dev.app.leaf.domain.rewrite.PlanAction.REWORD
import dev.app.leaf.domain.rewrite.PlanAction.SQUASH
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RebasePlanTest {
    private val someone = Identity("Author", "a@example.invalid")

    private fun commit(name: String, message: String, vararg parents: String) =
        Commit(name, message, someone, someone, 0, parents.toList().ifEmpty { listOf("base") })

    // feat, oldest first: a, b, c, d, e (tip). a, b and c are on origin/feat.
    private val a = commit("a", "Add the parser\n\nReads config files.\n", "base")
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
    private val onto = OntoBase("refs/heads/main", "m2")

    private fun RebasePlan.actions() = rows.map { it.commit.hash to it.action }

    private fun RebasePlan.order() = rows.joinToString("") { it.commit.hash }

    private fun RebasePlan.groupHashes() = groups.map { group -> group.commits.joinToString("") { it.hash } }

    @Test
    fun `a new plan picks every commit and changes nothing`() {
        assertEquals(listOf(a, b, c, d, e).map { it.hash to PICK }, plan.actions())
        assertFalse(plan.isChanged)
        assertEquals(5, plan.unchangedPrefix)
        assertEquals(listOf("a", "b", "c", "d", "e"), plan.groupHashes())
        assertEquals(0, plan.rewrittenPushed)
        assertEquals("e", plan.expectedTip)
    }

    @Test
    fun `squash and fixup join the row below them, skipping dropped rows, and the counts say what changed`() {
        val changed = plan.withAction("b", FIXUP).withAction("d", DROP).withAction("e", SQUASH).withAction("c", EDIT)

        assertEquals(listOf("ab", "ce"), changed.groupHashes())
        assertEquals(2, changed.joined)
        assertEquals(1, changed.dropped)
        assertEquals(1, changed.edits)
        assertFalse(changed.isReordered)
        assertTrue(changed.groups[1].stops)
        assertTrue(changed.isChanged)
    }

    @Test
    fun `the first kept row can't join the row below it, and one that lands first leads its own group`() {
        assertSame(plan, plan.withAction("a", SQUASH))

        val firstDropped = plan.withAction("b", FIXUP).withAction("a", DROP)

        assertEquals(listOf("b", "c", "d", "e"), firstDropped.groupHashes())
        assertTrue(firstDropped.groups.first().joinsNothing)
        // The row keeps its action: put the row below it back, and it joins again.
        assertEquals(listOf("ab", "c", "d", "e"), firstDropped.withAction("a", PICK).groupHashes())
    }

    @Test
    fun `a row moved to the bottom is picked, and rows move one place newer or older`() {
        val moved = plan.withAction("c", SQUASH).moved(2, 0)

        assertEquals("cabde", moved.order())
        assertEquals(PICK, moved.rows.first().action)
        assertTrue(moved.isReordered)
        assertEquals("abdce", plan.movedOne("c", towardsNewer = true).order())
        assertEquals("acbde", plan.movedOne("c", towardsNewer = false).order())
        assertSame(plan, plan.movedOne("e", towardsNewer = true))
        assertSame(plan, plan.moved(1, 1))
    }

    @Test
    fun `the unchanged rows at the start stay as they are, up to the first one that changes`() {
        assertEquals(2, plan.withAction("c", DROP).unchangedPrefix)
        // A row that the next one joins changes: it becomes another commit.
        assertEquals(1, plan.withAction("c", FIXUP).unchangedPrefix)
        assertEquals(4, plan.withAction("e", DROP).unchangedPrefix)
        assertEquals(1, plan.movedOne("b", towardsNewer = true).unchangedPrefix)
        // An edit stops there, so the rebase starts at it.
        assertEquals(2, plan.withAction("c", EDIT).unchangedPrefix)
        assertTrue(plan.withAction("e", EDIT).isChanged)
        assertEquals(0, plan.withOnto(onto).unchangedPrefix)
        assertTrue(plan.withOnto(onto).isChanged)
    }

    @Test
    fun `a reword changes a commit only when its message does`() {
        val sameMessage = plan.withAction("c", REWORD).withMessage("c", "Cache the parsed files\r\n")

        assertFalse(sameMessage.isChanged)
        assertTrue(plan.withMessage("c", "Cache parsed config files").isChanged)
        assertEquals(2, plan.withMessage("c", "Cache parsed config files").unchangedPrefix)
    }

    @Test
    fun `a row is replayed when the kept rows below it aren't the ones it had, in their order`() {
        val dropped = plan.withAction("b", DROP)

        assertEquals(listOf(false, false, true, true, true), dropped.rows.indices.map { dropped.isReplayed(it) })

        // After a swap, the rows above it are replayed too: in another order, the same changes can conflict.
        val moved = plan.movedOne("b", towardsNewer = true)

        assertEquals(listOf(false, true, true, true, true), moved.rows.indices.map { moved.isReplayed(it) })
        // Joining keeps the trees: nothing is replayed.
        val joined = plan.withAction("b", FIXUP).withAction("e", SQUASH)

        assertTrue(joined.rows.indices.none { joined.isReplayed(it) })
        assertTrue(plan.withOnto(onto).let { p -> p.rows.indices.all { p.isReplayed(it) } })
    }

    @Test
    fun `the pushed commits that a plan rewrites are those from its first change on`() {
        assertEquals(1, plan.withAction("c", DROP).rewrittenPushed)
        assertEquals(3, plan.withAction("b", FIXUP).rewrittenPushed)
        assertEquals(0, plan.withAction("e", DROP).rewrittenPushed)
        assertEquals(3, plan.withOnto(onto).rewrittenPushed)
    }

    @Test
    fun `a group of fixups keeps its first commit's message, and a squash starts from the subjects`() {
        val fixups = plan.withAction("b", FIXUP)

        assertNull(fixups.messageOf(fixups.groups.first()))
        assertEquals("Add the parser\n\nReads config files.", fixups.messageStart(fixups.groups.first()))

        val squashes = plan.withAction("b", SQUASH).withAction("c", SQUASH)

        assertEquals("Add the parser\n\nReads config files.\n\n- Cache the parsed files", squashes.messageOf(squashes.groups.first()))
        assertEquals(
            "Add the parser\n\nReads config files.\n\nwip\n\nCache the parsed files",
            squashes.messageStart(squashes.groups.first(), SquashMessageStart.ALL),
        )
        assertEquals(
            "Add the parser\n\nReads config files.",
            squashes.messageStart(squashes.groups.first(), SquashMessageStart.OLDEST),
        )
    }

    @Test
    fun `a message written for a group wins, a lone pick becomes a reword, and it can be taken back`() {
        val written = plan.withAction("b", SQUASH).withMessage("a", "Parse config files")

        assertEquals("Parse config files", written.messageOf(written.groups.first()))
        assertEquals("Add the parser\n\nReads config files.", written.withoutMessage("a").messageStart(written.groups.first(), SquashMessageStart.OLDEST))

        val reworded = plan.withMessage("d", "Test the parser cache")

        assertEquals(REWORD, reworded.rows[3].action)
        assertEquals("Test the parser cache", reworded.messageOf(reworded.groups[3]))
        // A row that joins another has no message of its own.
        assertSame(written, written.withMessage("b", "Ignored"))
    }

    @Test
    fun `amend! gives its message in place of the commit's, and squash! adds its body, as git rebase -i does`() {
        val amend = commit("f", "amend! Add the parser\n\nParse config files\n\nWith a cache.\n", "e")
        val squash = commit("g", "squash! Fix a typo\n\nAnd another one.\n", "f")
        val line = feat.copy(commits = listOf(squash, amend) + feat.commits)
        val withThem = RebasePlan.of(line, listOf(a, b, c, d, e, amend, squash)).withFixupsPlaced()

        assertEquals("Parse config files\n\nWith a cache.", withThem.messageOf(withThem.groups.first()))
        assertEquals("Fix a typo\n\n- And another one.", withThem.messageOf(withThem.groups.last()))
        assertEquals(
            "Fix a typo\n\nAnd another one.",
            withThem.messageStart(withThem.groups.last(), SquashMessageStart.ALL),
        )
    }

    @Test
    fun `fixup, squash and amend commits go under the commits they name, in the order git's autosquash gives`() {
        // git rebase -i --autosquash on git 2.54: pick a, fixup f, fixup -C h, pick b, squash g.
        val addA = commit("a", "Add a\n\nBody of a\n")
        val addB = commit("b", "Add b\n", "a")
        val fixA = commit("f", "fixup! Add a\n", "b")
        val squashB = commit("g", "squash! Add b\n\nSquash body\n", "f")
        val amendA = commit("h", "amend! Add a\n\nAdd a, better\n\nNew body of a\n", "g")
        val line = BranchLine("refs/heads/feat", "refs/heads/main", listOf(amendA, squashB, fixA, addB, addA))
        val before = RebasePlan.of(line, listOf(addA, addB, fixA, squashB, amendA))

        assertEquals(listOf(fixA, squashB, amendA), before.misplacedFixups.map { it.commit })

        val placed = before.withFixupsPlaced()

        assertEquals(listOf("a" to PICK, "f" to FIXUP, "h" to FIXUP, "b" to PICK, "g" to SQUASH), placed.actions())
        assertTrue(placed.misplacedFixups.isEmpty())
        assertEquals("Add a, better\n\nNew body of a", placed.messageOf(placed.groups[0]))
        assertEquals("Add b\n\n- Squash body", placed.messageOf(placed.groups[1]))
        assertSame(placed.rows, placed.withFixupsPlaced().rows)
    }

    @Test
    fun `a fixup! commit that names nothing below it, or is dropped, isn't moved`() {
        val orphan = commit("f", "fixup! Something else\n", "e")
        val early = commit("g", "fixup! Fix a typo\n", "base")
        val line = BranchLine("refs/heads/feat", "refs/heads/main", listOf(orphan, e, early))
        val withThem = RebasePlan.of(line, listOf(early, e, orphan))

        // The commit early names is above it, not below.
        assertTrue(withThem.misplacedFixups.isEmpty())
        assertTrue(RebasePlan.of(feat.copy(commits = listOf(orphan) + feat.commits), listOf(a, b, c, d, e, orphan)).misplacedFixups.isEmpty())

        val dropped = RebasePlan.of(feat.copy(commits = listOf(early.copy(hash = "h")) + feat.commits), listOf(a, b, c, d, e, early.copy(hash = "h")))
            .withAction("h", DROP)

        assertTrue(dropped.misplacedFixups.isEmpty())
    }

    @Test
    fun `a fixup! commit whose commit is dropped isn't moved under another one`() {
        val fixTypo = commit("f", "fixup! Fix a typo\n", "e")
        val withFix = RebasePlan.of(feat.copy(commits = listOf(fixTypo) + feat.commits), listOf(a, b, c, d, e, fixTypo))
            .withAction("e", DROP)

        assertTrue(withFix.misplacedFixups.isEmpty())
        assertEquals(withFix.rows, withFix.withFixupsPlaced().rows)
    }

    @Test
    fun `merges can be kept as they are, or dropped, but not joined, edited, replayed or picked again by git`() {
        val merge = commit("m", "Merge main into feat\n", "b", "main1")
        val after = commit("x", "After the merge\n", "m")
        val line = BranchLine("refs/heads/feat", "refs/heads/main", listOf(after, merge, b, a))
        val withMerge = RebasePlan.of(line, listOf(a, b, merge, after))

        assertNull(withMerge.refusal())
        assertNull(withMerge.withAction("x", DROP).refusal())
        assertNull(withMerge.withAction("m", DROP).refusal())
        assertEquals(PlanRefusal.JoinsMerge("m"), withMerge.withAction("x", FIXUP).refusal())
        assertEquals(PlanRefusal.JoinsMerge("m"), withMerge.withAction("m", SQUASH).refusal())
        assertEquals(PlanRefusal.JoinsMerge("m"), withMerge.withAction("m", EDIT).refusal())
        assertEquals(PlanRefusal.ReplaysMerge("m"), withMerge.withAction("b", DROP).refusal())
        assertEquals(PlanRefusal.ReplaysMerge("m"), withMerge.withOnto(onto).refusal())
        // A plan that stops goes through git rebase -i, which can't pick a merge that the plan changes after.
        assertEquals(PlanRefusal.MergeInRebase("m"), withMerge.withMessage("m", "Merge main").refusal(stops = true))
        assertEquals(PlanRefusal.MergeInRebase("m"), withMerge.withAction("b", EDIT).refusal())
        assertNull(withMerge.withAction("x", EDIT).refusal())
    }

    @Test
    fun `moving onto the latest base needs the whole branch, read to its first commit, and a base`() {
        assertEquals(onto, plan.withOnto(onto).onto)
        assertNull(plan.withOnto(onto).withOnto(null).onto)

        val fromC = RebasePlan.of(feat, listOf(c, d, e))

        assertFalse(fromC.canMoveOnto)
        assertSame(fromC, fromC.withOnto(onto))
        assertFalse(RebasePlan.of(feat.copy(isTruncated = true), listOf(a, b, c, d, e)).canMoveOnto)
        assertFalse(RebasePlan.of(feat.copy(base = null), listOf(a, b, c, d, e)).canMoveOnto)
    }

    @Test
    fun `reset puts every row back as a pick, and keeps where the plan goes`() {
        val changed = plan.withOnto(onto).withAction("b", FIXUP).movedOne("d", towardsNewer = true).withMessage("a", "New")
        val reset = changed.reset()

        assertEquals(plan.rows, reset.rows)
        assertTrue(reset.messages.isEmpty())
        assertEquals(onto, reset.onto)
    }
}
