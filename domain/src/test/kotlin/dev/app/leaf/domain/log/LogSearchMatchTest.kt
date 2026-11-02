// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the log's search matches: the loaded commits and the walk of the whole history ask the same function. */
class LogSearchMatchTest {
    private val commit = Commit(
        hash = "0a1b2c3d4e5f60718293a4b5c6d7e8f901234567",
        message = "Fix the Parser for quoted paths\n\nLonger text about SIGPIPE.",
        committer = Identity("Grace Hopper", "grace@example.com"),
        author = Identity("Ada Lovelace", "ada@example.com"),
        date = 0L,
        parentsHashes = emptyList(),
    )

    private fun matches(lowercaseTerm: String) = commit.matchesLogSearch(lowercaseTerm)

    @Test
    fun `the message matches anywhere in it, in any case`() {
        assertTrue(matches("parser"))
        assertTrue(matches("sigpipe"))
        assertTrue(matches("quoted paths"))
    }

    @Test
    fun `the author's and the committer's names match, in any case`() {
        assertTrue(matches("lovelace"))
        assertTrue(matches("grace hopper"))
    }

    @Test
    fun `the hash matches anywhere in it, not only at its start`() {
        assertTrue(matches("0a1b2c3"))
        assertTrue(matches("a4b5c6d7"))
        assertTrue(matches("901234567"))
    }

    @Test
    fun `an email isn't searched, as it never was`() {
        assertFalse(matches("example.com"))
        assertFalse(matches("ada@"))
    }

    @Test
    fun `a term that nothing has matches nothing`() {
        assertFalse(matches("zzz"))
    }

    @Test
    fun `a missing name is no name, and doesn't fail`() {
        assertTrue(matchesLogSearch("fix", "abc", "Fix it", authorName = null, committerName = null))
        assertFalse(matchesLogSearch("nobody", "abc", "Fix it", authorName = null, committerName = null))
    }

    @Test
    fun `the function and the commit's extension agree`() {
        for (term in listOf("parser", "lovelace", "a4b5c6", "example.com", "zzz")) {
            assertEquals(
                matchesLogSearch(term, commit.hash, commit.message, commit.author.name, commit.committer.name),
                commit.matchesLogSearch(term),
                term,
            )
        }
    }
}
