// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val PERSON = Identity("Ann Lee", "ann@acme.dev")

private fun commit(subject: String, parents: Int = 1) =
    Commit("0".repeat(40), "$subject\n\nBody.", PERSON, PERSON, 0L, List(parents) { "p$it" })

private fun history(vararg subjects: String) = conventionalHistory(subjects.map { commit(it) })

class ConventionalCommitsTest {
    // --- The history

    @Test
    fun `counts the types and the scopes of each type`() {
        val history = history(
            "feat(clone): list tags",
            "feat(clone)!: drop the old dialog",
            "fix(log): keep the row",
            "Fix: capitalised",
            "deps: bump ktor",
            "chore: tidy",
            "Merge the clone work",
        )

        assertEquals(mapOf("feat" to 2, "fix" to 2, "deps" to 1, "chore" to 1), history.typeUses)
        assertEquals(mapOf("feat" to mapOf("clone" to 2), "fix" to mapOf("log" to 1)), history.scopeUses)
        assertEquals(6, history.conventionalSubjects)
        assertEquals(7, history.sampled)
    }

    @Test
    fun `a subject needs the colon, a space and a description to count`() {
        val history = history("feat:no space", "feat: ", "feat(): empty scope", "feat (x): spaced", "wip", "feat: ok")

        assertEquals(1, history.conventionalSubjects)
        assertEquals(mapOf("feat" to 1), history.typeUses)
    }

    @Test
    fun `merge commits don't count, as git writes their subjects`() {
        val commits = listOf(commit("Merge branch 'x'", parents = 2), commit("feat: a"), commit("Merge branch 'y'", parents = 2))

        val history = conventionalHistory(commits)

        assertEquals(1, history.sampled)
        assertTrue(history.usesConventionalCommits)
    }

    @Test
    fun `a repository uses Conventional Commits when at least half of its newest 50 subjects do`() {
        fun history(conventional: Int, others: Int, older: Int = 0) = conventionalHistory(
            List(conventional) { commit("feat: change $it") } + List(others) { commit("Change $it") } +
                List(older) { commit("feat: an older change $it") }
        )

        assertTrue(history(conventional = 25, others = 25).usesConventionalCommits)
        assertFalse(history(conventional = 24, others = 26).usesConventionalCommits)
        // Only the newest 50 decide; older subjects still count for the types' uses
        assertFalse(history(conventional = 0, others = 50, older = 100).usesConventionalCommits)
        assertEquals(100, history(conventional = 0, others = 50, older = 100).typeUses["feat"])
        assertTrue(history(conventional = 2, others = 1).usesConventionalCommits)
        assertFalse(conventionalHistory(emptyList()).usesConventionalCommits)
    }

    // --- Where the caret is

    @Test
    fun `the caret types a type at the subject's start, and a scope after its parenthesis`() {
        assertEquals(ConventionalQuery.Type("fe", 2), conventionalQueryAt("fe", 2))
        assertEquals(ConventionalQuery.Type("Fe", 2), conventionalQueryAt("Fe\n\nBody", 2))
        assertEquals(ConventionalQuery.Scope("feat", "cl", 7), conventionalQueryAt("feat(cl", 7))
        assertEquals(ConventionalQuery.Scope("feat", "", 5), conventionalQueryAt("Feat(", 5))
    }

    @Test
    fun `nothing to complete elsewhere`() {
        assertNull(conventionalQueryAt("", 0), "An empty subject")
        assertNull(conventionalQueryAt("feat", 2), "Not at the line's end")
        assertNull(conventionalQueryAt("feat: x", 7), "A whole subject")
        assertNull(conventionalQueryAt("Fix the parser", 14), "A plain subject")
        assertNull(conventionalQueryAt("feat(clone)", 11), "A closed scope")
        assertNull(conventionalQueryAt("feat(a b", 8))
        assertNull(conventionalQueryAt("Subject\nfe", 10), "Not the first line")
        assertNull(conventionalQueryAt("1fix", 4))
    }

    // --- The choices

    @Test
    fun `types that start with what was typed, the most used first, then the usual ones in their order`() {
        val history = history("fix: a", "fix: b", "fix: c", "feat: d", "deps: e", "deps: f", "deps: g", "deps: h")

        fun names(typed: String) = commitTypeChoices(typed, history).map { it.name }

        assertEquals(listOf("fix", "feat"), names("f"))
        assertEquals(listOf("fix", "feat"), names("F"))
        assertEquals(listOf("deps", "docs"), names("d"))
        assertEquals(
            listOf("deps", "fix", "feat", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore", "revert"),
            commitTypeChoices("", history).map { it.name },
        )
        assertEquals(3, commitTypeChoices("fix", history).single().uses)
        assertEquals(emptyList<String>(), names("x"))
    }

    @Test
    fun `a commitlint list keeps only its types and adds its own, and a never list leaves its types out`() {
        val history = history("fix: a", "deps: b")
        val allowed = CommitlintRules("x", types = EnumRule(setOf("feat", "fix", "security"), allows = true))
        val excluded = CommitlintRules("x", types = EnumRule(setOf("deps", "chore"), allows = false))

        assertEquals(listOf("fix", "feat", "security"), commitTypeChoices("", history, allowed).map { it.name })
        assertEquals(
            listOf("fix", "feat", "docs", "style", "refactor", "perf", "test", "build", "ci", "revert"),
            commitTypeChoices("", history, excluded).map { it.name },
        )
    }

    @Test
    fun `scopes of the type the most used first, or the history's scopes when the type has none`() {
        val history = history(
            "feat(clone): a", "feat(clone): b", "feat(conflicts): c", "feat(commit-field): d", "feat(commit-field): e",
            "feat(commit-field): f", "fix(log): g", "fix(clone): h",
        )

        assertEquals(listOf("commit-field", "clone", "conflicts"), commitScopeChoices("feat", "c", history).map { it.name })
        assertEquals(listOf("clone", "log"), commitScopeChoices("fix", "", history).map { it.name })
        assertEquals(
            listOf("clone", "commit-field", "conflicts", "log"),
            commitScopeChoices("docs", "", history).map { it.name },
            "docs has no scopes: all of them, with their uses summed (3, 3, 1, 1), a tie by name",
        )
        assertEquals(
            listOf("ui"),
            commitScopeChoices("feat", "", history, CommitlintRules("x", scopes = EnumRule(setOf("ui"), allows = true)))
                .map { it.name },
        )
    }

    @Test
    fun `the branch's ticket key is the first scope, if the typing and the config allow it`() {
        val history = history("feat(clone): a", "feat(clone): b", "feat(log): c")

        val choices = commitScopeChoices("feat", "", history, branchKey = "PROJ-1")
        assertEquals(listOf("PROJ-1", "clone", "log"), choices.map { it.name })
        assertEquals(listOf(true, false, false), choices.map { it.isFromBranch })

        assertEquals(listOf("clone"), commitScopeChoices("feat", "c", history, branchKey = "PROJ-1").map { it.name })
        assertEquals(
            listOf("ui"),
            commitScopeChoices(
                "feat", "", history, CommitlintRules("x", scopes = EnumRule(setOf("ui"), allows = true)), "PROJ-1",
            ).map { it.name },
        )
    }

    @Test
    fun `when the field suggests types, as the setting says`() {
        val conventional = history("feat: a", "fix: b")
        val plain = history("Add a", "Fix b")
        val config = CommitlintRules("commitlint.config.js")

        assertTrue(ConventionalCommitsMode.AUTOMATIC.suggestsIn(conventional, null))
        assertFalse(ConventionalCommitsMode.AUTOMATIC.suggestsIn(plain, null))
        assertTrue(ConventionalCommitsMode.AUTOMATIC.suggestsIn(plain, config))
        assertTrue(ConventionalCommitsMode.ALWAYS.suggestsIn(plain, null))
        assertFalse(ConventionalCommitsMode.OFF.suggestsIn(conventional, config))
    }

    // --- Completing

    @Test
    fun `a type becomes type colon space, and a scope type(scope) colon space, with the rest kept`() {
        val (typeText, typeCaret) = withConventionalChoice("fe\n\nBody", ConventionalQuery.Type("fe", 2), ConventionalChoice("feat", 3))
        assertEquals("feat: \n\nBody", typeText)
        assertEquals(6, typeCaret)

        val (scopeText, scopeCaret) =
            withConventionalChoice("Feat(cl", ConventionalQuery.Scope("feat", "cl", 7), ConventionalChoice("clone", 2))
        assertEquals("feat(clone): ", scopeText)
        assertEquals(scopeText.length, scopeCaret)
    }
}
