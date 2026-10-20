// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun graphCommit(hash: String) = GraphCommit(
    commit = Commit(
        hash = hash,
        message = "Fix $hash",
        committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        date = 0L,
        parentsHashes = emptyList(),
    ),
    lane = 0,
    passingLanes = emptyList(),
    mergingLanes = emptyList(),
    forkingOffLanes = emptyList(),
    childCount = 1,
)

/** What the log's rows read from the search: which commits match, and which match the counter points at. */
class LogSearchTest {
    private val first = graphCommit("a1")
    private val second = graphCommit("b2")
    private val third = graphCommit("c3")

    @Test
    fun `a search with a term has its commits as the matches`() {
        val results = LogSearch.SearchResults(listOf(first, second, third), index = 1)

        assertEquals(listOf(first, second, third), results.matchingCommits)
    }

    @Test
    fun `a term that nothing matches has no matches, so every row is a non-match`() {
        val results = LogSearch.SearchResults(emptyList(), index = 0)

        assertEquals(emptyList<GraphCommit>(), results.matchingCommits)
        assertNull(results.focusedCommit)
    }

    @Test
    fun `an empty search field marks no row, as nothing is searched for`() {
        val results = LogSearch.SearchResults(emptyList(), index = 0, hasTerm = false)

        assertNull(results.matchingCommits)
        assertNull(results.focusedCommit)
    }

    @Test
    fun `the focused commit is the one the counter points at, which starts at 1`() {
        val results = LogSearch.SearchResults(listOf(first, second, third), index = 1)

        assertSame(first, results.focusedCommit)
        assertSame(second, results.copy(index = 2).focusedCommit)
        assertSame(third, results.copy(index = 3).focusedCommit)
    }

    @Test
    fun `no commit is focused when the counter points at no result`() {
        val results = LogSearch.SearchResults(listOf(first, second), index = 0)

        assertNull(results.focusedCommit)
        assertNull(results.copy(index = 3).focusedCommit)
        assertNull(results.copy(index = -1).focusedCommit)
    }

    @Test
    fun `an empty field focuses nothing even with an index`() {
        assertNull(LogSearch.SearchResults(listOf(first), index = 1, hasTerm = false).focusedCommit)
    }

    @Test
    fun `the counter's total is the number of matches`() {
        assertEquals(3, LogSearch.SearchResults(listOf(first, second, third), index = 2).totalCount)
        assertTrue(LogSearch.SearchResults(emptyList(), index = 0).hasTerm)
    }
}
