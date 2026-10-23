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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RebaseTodoTest {
    private val someone = Identity("Author", "a@example.invalid")

    private fun commit(name: String, message: String, vararg parents: String) =
        Commit(name, message, someone, someone, 0, parents.toList())

    // feat, oldest first: a, b, c, d (tip), on base
    private val a = commit("a", "Add the parser\n", "base")
    private val b = commit("b", "wip\n", "a")
    private val c = commit("c", "Cache the parsed files\n", "b")
    private val d = commit("d", "Test the cache\n", "c")
    private val line = BranchLine("refs/heads/feat", "refs/heads/main", listOf(d, c, b, a))
    private val plan = RebasePlan.of(line, listOf(a, b, c, d))

    /** A check that ran through: each group, with [empty] commits. */
    private fun clean(plan: RebasePlan, vararg empty: String) = PlanCheck(
        plan.groups.map { group ->
            CheckedGroup(group.target.commit.hash, group.commits.map { it.hash }, "tree", group.commits.map { it.hash }.filter { it in empty }.toSet())
        },
        stop = null,
    )

    @Test
    fun `the todo starts after the rows that stay, drops what the plan drops, and joins with fixups`() {
        val changed = plan.withAction("b", DROP).movedOne("d", towardsNewer = false).withAction("c", FIXUP)

        // a, b (dropped), d, c joining d
        val todo = changed.rebaseTodo(clean(changed), twins = emptyMap())

        assertEquals(listOf("drop b", "pick d", "fixup c"), todo.lines)
        assertEquals("a", todo.upstream)
        assertEquals("a", todo.onto)
        assertEquals(emptyList<TwinCommit>(), changed.twinsFor(clean(changed)))
    }

    @Test
    fun `a new message goes in a twin, picked in place of the commit, which is listed as dropped`() {
        val reworded = plan.withMessage("c", "Cache parsed config files").withAction("b", DROP)
        val check = clean(reworded)

        assertEquals(listOf(TwinCommit("c", "c", "c", "Cache parsed config files")), reworded.twinsFor(check))
        assertEquals(
            listOf("drop b", "drop c", "pick twin-c", "pick d"),
            reworded.rebaseTodo(check, mapOf("c" to "twin-c")).lines,
        )
    }

    @Test
    fun `a squash's message goes in a twin too, and every row that joins it is a fixup`() {
        val squashed = plan.withAction("c", SQUASH).withAction("d", SQUASH).withAction("a", DROP)
        val check = clean(squashed)

        // The default message starts from the subjects: wip says nothing, so the next one leads
        assertEquals(listOf(TwinCommit("b", "b", "b", "Cache the parsed files\n\n- Test the cache")), squashed.twinsFor(check))
        assertEquals(
            listOf("drop a", "drop b", "pick twin-b", "fixup c", "fixup d"),
            squashed.rebaseTodo(check, mapOf("b" to "twin-b")).lines,
        )
        assertEquals("base", squashed.rebaseTodo(check, emptyMap()).upstream)
    }

    @Test
    fun `an edit is an edit line, of the twin when the commit gets a new message`() {
        val edited = plan.withAction("c", EDIT)

        assertEquals(listOf("edit c", "pick d"), edited.rebaseTodo(clean(edited), emptyMap()).lines)

        val reworded = edited.withMessage("c", "Cache them")

        assertEquals(listOf("drop c", "edit twin-c", "pick d"), reworded.rebaseTodo(clean(reworded), mapOf("c" to "twin-c")).lines)
    }

    @Test
    fun `a group whose first commit came out empty starts from its first commit that isn't, with the group's message`() {
        val joined = plan.withAction("c", FIXUP).withOnto(OntoBase("refs/heads/main", "m"))
        val check = clean(joined, "b")

        assertEquals(listOf(TwinCommit("b", "c", "b", "wip\n")), joined.twinsFor(check))
        assertEquals(
            listOf("pick a", "drop b", "drop c", "pick twin-b", "pick d"),
            joined.rebaseTodo(check, mapOf("b" to "twin-b")).lines,
        )
        assertEquals("base", joined.rebaseTodo(check, emptyMap()).upstream)
        assertEquals("m", joined.rebaseTodo(check, emptyMap()).onto)
    }

    @Test
    fun `a group that came out empty whole is dropped, so that nothing joins the commit below`() {
        val joined = plan.withAction("c", FIXUP).withOnto(OntoBase("refs/heads/main", "m"))
        val check = clean(joined, "b", "c")

        assertEquals(emptyList<TwinCommit>(), joined.twinsFor(check))
        assertEquals(listOf("pick a", "drop b", "drop c", "pick d"), joined.rebaseTodo(check, emptyMap()).lines)
    }

    @Test
    fun `groups after the check's stop are written as the plan has them`() {
        val stopped = plan.withAction("a", DROP).withAction("d", FIXUP)
        val check = PlanCheck(emptyList(), PlanStop.Conflict("b", listOf("x.txt")))

        assertEquals(listOf("drop a", "pick b", "pick c", "fixup d"), stopped.rebaseTodo(check, emptyMap()).lines)
    }

    @Test
    fun `a branch from the repository's first commit has no upstream, so the rebase starts at the root`() {
        val root = commit("r", "Root\n")
        val next = commit("n", "Next\n", "r")
        val rootPlan = RebasePlan.of(BranchLine("refs/heads/feat", null, listOf(next, root)), listOf(root, next)).withAction("r", DROP)

        assertNull(rootPlan.rebaseTodo(clean(rootPlan), emptyMap()).upstream)
    }
}
