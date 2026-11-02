// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.SearchHit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun hitsOf(vararg hashes: String) = SearchHits(hashes.mapIndexed { depth, hash -> SearchHit(hash, depth * 10) })

/** What the log's rows and counter read from the search: which commits match, and which match the counter points at. */
class LogSearchTest {
    private val hits = hitsOf("a1", "b2", "c3")

    @Test
    fun `a search with a term has its hits as the matches`() {
        val results = LogSearch.SearchResults(hits, index = 1)

        assertEquals(setOf("a1", "b2", "c3"), results.matchingHashes)
    }

    @Test
    fun `a term that nothing matches has no matches, so every row is a non-match`() {
        val results = LogSearch.SearchResults(SearchHits.None, index = 0)

        assertEquals(emptySet<String>(), results.matchingHashes)
        assertNull(results.focusedHit)
    }

    @Test
    fun `an empty search field marks no row, as nothing is searched for`() {
        val results = LogSearch.SearchResults.NoTerm

        assertNull(results.matchingHashes)
        assertNull(results.focusedHit)
        assertFalse(results.hasTerm)
    }

    @Test
    fun `the focused hit is the one the counter points at, which starts at 1`() {
        val results = LogSearch.SearchResults(hits, index = 1)

        assertEquals("a1", results.focusedHit?.hash)
        assertEquals("b2", results.copy(index = 2).focusedHit?.hash)
        assertEquals("c3", results.copy(index = 3).focusedHit?.hash)
        assertEquals(20, results.copy(index = 3).focusedHit?.depth)
    }

    @Test
    fun `no hit is focused when the counter points at no result`() {
        val results = LogSearch.SearchResults(hitsOf("a1", "b2"), index = 0)

        assertNull(results.focusedHit)
        assertNull(results.copy(index = 3).focusedHit)
        assertNull(results.copy(index = -1).focusedHit)
    }

    @Test
    fun `an empty field focuses nothing even with an index`() {
        assertNull(LogSearch.SearchResults(hitsOf("a1"), index = 1, hasTerm = false).focusedHit)
    }

    @Test
    fun `the counter's total is the number of hits`() {
        assertEquals(3, LogSearch.SearchResults(hits, index = 2).totalCount)
        assertTrue(LogSearch.SearchResults(SearchHits.None, index = 0).hasTerm)
    }

    @Test
    fun `the counter says a plus while the walk runs or when it stopped at the limit`() {
        assertEquals("3", LogSearch.SearchResults(hits, index = 1).totalText)
        assertEquals("3+", LogSearch.SearchResults(hits, index = 1, isSearching = true).totalText)
        assertEquals("3+", LogSearch.SearchResults(hits, index = 1, isCapped = true).totalText)
        assertEquals("0", LogSearch.SearchResults(SearchHits.None, index = 0).totalText)
    }

    @Test
    fun `the counter has no number yet while the walk runs and nothing is found`() {
        assertEquals("…", LogSearch.SearchResults(SearchHits.None, index = 0, isSearching = true).totalText)
    }

    @Test
    fun `a hit's number is where it stands in the list, from 1, and 0 for a commit that isn't one`() {
        assertEquals(1, hits.numberOf("a1"))
        assertEquals(3, hits.numberOf("c3"))
        assertEquals(0, hits.numberOf("zz"))
    }

    @Test
    fun `taking a hit out keeps the order of the others`() {
        val without = hits.without("b2")

        assertEquals(listOf("a1", "c3"), without.list.map { it.hash })
        assertEquals(2, without.numberOf("c3"))
        assertEquals(setOf("a1", "c3"), without.hashes)
    }

    @Test
    fun `the copies that only move the index share one set of hashes`() {
        val results = LogSearch.SearchResults(hits, index = 1)

        assertSame(results.matchingHashes, results.copy(index = 2).matchingHashes)
    }
}
