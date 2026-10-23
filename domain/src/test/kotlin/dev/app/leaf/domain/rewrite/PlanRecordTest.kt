// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.PlanAction.DROP
import dev.app.leaf.domain.rewrite.PlanAction.FIXUP
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlanRecordTest {
    private val someone = Identity("Author", "a@example.invalid")

    private fun commit(name: String, message: String, vararg parents: String) =
        Commit(name, message, someone, someone, 0, parents.toList())

    private val a = commit("a", "Add the parser\n", "base")
    private val b = commit("b", "wip\n", "a")
    private val c = commit("c", "Cache the parsed files\n", "b")
    private val line = BranchLine("refs/heads/feat", "refs/heads/main", listOf(c, b, a))
    private val plan = RebasePlan.of(line, listOf(a, b, c))
        .withAction("b", FIXUP)
        .movedOne("c", towardsNewer = false)
        .withMessage("a", "Parse config files\n\nWith a cache: “fast”, ünïcode\tand tabs.")
        .withOnto(OntoBase("refs/heads/main", "m"))

    @Test
    fun `a record gives the plan back, messages and all, on the branch's line`() {
        val record = PlanRecord.of(plan, twins = mapOf("a" to "twin-a"))
        val parsed = PlanRecord.parse(record.format())

        assertEquals(record, parsed)
        assertEquals(plan, parsed!!.planOn(line))
        assertEquals("a", parsed.originalOf("twin-a"))
        assertEquals("c", parsed.originalOf("c"))
    }

    @Test
    fun `a record is for the branch at its tip, so a branch that moved, or another one, has no plan`() {
        val record = PlanRecord.of(plan, emptyMap())
        val moved = commit("d", "An agent's commit\n", "c")

        assertNull(record.planOn(line.copy(commits = listOf(moved) + line.commits)))
        assertNull(record.planOn(line.copy(branch = "refs/heads/other")))
        // A plan from a commit gives the line from it
        val fromB = RebasePlan.of(line, listOf(b, c)).withAction("b", DROP)

        assertEquals(fromB, PlanRecord.of(fromB, emptyMap()).planOn(line))
    }

    @Test
    fun `text that isn't a record, or of a later version, is no record`() {
        assertNull(PlanRecord.parse(""))
        assertNull(PlanRecord.parse("leaf-plan 2\nbranch refs/heads/feat\ntip c\n"))
        assertNull(PlanRecord.parse("leaf-plan 1\nbranch refs/heads/feat\n"))
        assertNull(PlanRecord.parse("leaf-plan 1\nbranch refs/heads/feat\ntip c\nrow a JUGGLE\n"))
        assertNull(PlanRecord.parse("leaf-plan 1\nbranch refs/heads/feat\ntip c\nmessage a not-base64!\n"))
    }
}
