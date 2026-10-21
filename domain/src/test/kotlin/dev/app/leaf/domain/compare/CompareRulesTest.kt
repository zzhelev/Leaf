// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.compare

import dev.app.leaf.domain.log.LogRef
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.ComparisonMode.ALL
import dev.app.leaf.domain.models.ComparisonMode.SINCE_SPLIT
import dev.app.leaf.domain.models.ComparisonRelation
import dev.app.leaf.domain.models.ComparisonRelation.A_IS_ANCESTOR
import dev.app.leaf.domain.models.ComparisonRelation.B_IS_ANCESTOR
import dev.app.leaf.domain.models.ComparisonRelation.DIVERGED
import dev.app.leaf.domain.models.ComparisonRelation.SAME
import dev.app.leaf.domain.models.ComparisonRelation.UNRELATED
import dev.app.leaf.domain.models.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompareRulesTest {
    private val commit = "5d5537fe0f2a4b8c9d1e3f405162738495a6b7c8"

    @Test
    fun `a row's side is its first branch, in the order of its chips`() {
        val refs = listOf(
            LogRef.LocalBranch(branch("refs/heads/main")),
            LogRef.LocalBranch(branch("refs/heads/feature")),
            LogRef.RemoteBranch(branch("refs/remotes/origin/main", isLocal = false)),
            LogRef.TagRef(tag("refs/tags/v1")),
        )

        assertEquals(CompareSide.Branch("refs/heads/main", commit), compareSideOf(commit, refs))
    }

    @Test
    fun `a remote branch is a branch to follow too`() {
        val refs = listOf(LogRef.RemoteBranch(branch("refs/remotes/origin/feature", isLocal = false)), LogRef.TagRef(tag("refs/tags/v1")))

        assertEquals(CompareSide.Branch("refs/remotes/origin/feature", commit), compareSideOf(commit, refs))
    }

    @Test
    fun `a detached HEAD isn't a branch, so the next branch is, or else the commit`() {
        val head = LogRef.LocalBranch(branch("HEAD"))

        assertEquals(
            CompareSide.Branch("refs/heads/feature", commit),
            compareSideOf(commit, listOf(head, LogRef.LocalBranch(branch("refs/heads/feature")))),
        )
        assertEquals(CompareSide.Commit(commit), compareSideOf(commit, listOf(head)))
    }

    @Test
    fun `a row without a branch is its commit, named by its first tag or its short id`() {
        val tagged = compareSideOf(commit, listOf(LogRef.TagRef(tag("refs/tags/v1.2")), LogRef.TagRef(tag("refs/tags/v1.3"))))
        val plain = compareSideOf(commit, emptyList())

        assertEquals(CompareSide.Commit(commit, tag = "refs/tags/v1.2"), tagged)
        assertEquals(commit, tagged.revision)
        assertEquals("v1.2", tagged.name)
        assertEquals(CompareSide.Commit(commit), plain)
        assertEquals("5d5537f", plain.name)
    }

    @Test
    fun `a branch is compared by its full name and named by its short one`() {
        val side = CompareSide.Branch("refs/remotes/origin/feat/clone-picker", commit)

        assertEquals("refs/remotes/origin/feat/clone-picker", side.revision)
        assertEquals("origin/feat/clone-picker", side.name)
    }

    @Test
    fun `picked sides go older first, and equal dates keep the order they were picked in`() {
        val older = CompareSide.Commit("a".repeat(40))
        val newer = CompareSide.Commit("b".repeat(40))

        assertEquals(older to newer, inPickedOrder(older, 100, newer, 200))
        assertEquals(older to newer, inPickedOrder(newer, 200, older, 100))
        assertEquals(newer to older, inPickedOrder(newer, 100, older, 100))
    }

    @Test
    fun `only an ancestor that ended up as B makes a new comparison swap`() {
        assertEquals(
            listOf(B_IS_ANCESTOR),
            ComparisonRelation.entries.filter { it.putsAncestorFirst },
        )
    }

    @Test
    fun `two branches start since they split, anything else with all differences`() {
        val branch = CompareSide.Branch("refs/heads/main", commit)
        val other = CompareSide.Branch("refs/remotes/origin/feature", commit)
        val tagged = CompareSide.Commit(commit, tag = "refs/tags/v1")

        assertEquals(SINCE_SPLIT, defaultMode(branch, other))
        assertEquals(ALL, defaultMode(branch, tagged))
        assertEquals(ALL, defaultMode(tagged, branch))
        assertEquals(ALL, defaultMode(CompareSide.Commit(commit), tagged))
    }

    @Test
    fun `the switch shows only for sides that diverged`() {
        assertEquals(listOf(DIVERGED), ComparisonRelation.entries.filter { it.showsModeSwitch })
    }

    @Test
    fun `since they split shows where it lists what B did, all differences otherwise`() {
        assertEquals(SINCE_SPLIT, DIVERGED.modeToShow(SINCE_SPLIT))
        assertEquals(ALL, DIVERGED.modeToShow(ALL))
        // The same changes either way, so no second read
        assertEquals(SINCE_SPLIT, A_IS_ANCESTOR.modeToShow(SINCE_SPLIT))
        assertEquals(SINCE_SPLIT, SAME.modeToShow(SINCE_SPLIT))
        // B did nothing since they split, or they never split
        assertEquals(ALL, B_IS_ANCESTOR.modeToShow(SINCE_SPLIT))
        assertEquals(ALL, UNRELATED.modeToShow(SINCE_SPLIT))

        for (relation in ComparisonRelation.entries) {
            assertEquals(ALL, relation.modeToShow(ALL), "$relation")
        }
    }

    @Test
    fun `a swap turns which side is the ancestor around, and keeps the other relations`() {
        assertEquals(B_IS_ANCESTOR, A_IS_ANCESTOR.swapped)
        assertEquals(A_IS_ANCESTOR, B_IS_ANCESTOR.swapped)

        for (relation in listOf(SAME, DIVERGED, UNRELATED)) {
            assertEquals(relation, relation.swapped)
        }
    }

    @Test
    fun `the relation comes from the commits that each side has alone`() {
        assertEquals(SAME, comparisonRelation(countOnlyInA = 0, countOnlyInB = 0, hasMergeBase = true))
        assertEquals(A_IS_ANCESTOR, comparisonRelation(countOnlyInA = 0, countOnlyInB = 3, hasMergeBase = true))
        assertEquals(B_IS_ANCESTOR, comparisonRelation(countOnlyInA = 4, countOnlyInB = 0, hasMergeBase = true))
        assertEquals(DIVERGED, comparisonRelation(countOnlyInA = 4, countOnlyInB = 2, hasMergeBase = true))
        assertEquals(UNRELATED, comparisonRelation(countOnlyInA = 1811, countOnlyInB = 37, hasMergeBase = false))
    }

    private fun branch(name: String, isLocal: Boolean = true) = Branch(commit, name, isLocal)

    private fun tag(name: String) = Tag(commitHash = commit, hash = "f".repeat(40), name = name)
}
