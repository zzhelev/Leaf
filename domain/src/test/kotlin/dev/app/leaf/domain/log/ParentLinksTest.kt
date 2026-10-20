// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(hash: String, message: String = "Subject of $hash", parents: List<String> = emptyList()) = Commit(
    hash = hash,
    message = message,
    committer = someone,
    author = someone,
    date = 0L,
    parentsHashes = parents,
)

private fun hash(n: Int) = n.toString(16).padStart(7, '0') + "0".repeat(33)

/** What the commit details list as a commit's parents (fork-only, upstream issue 270). */
class ParentLinksTest {
    private val nothingLoaded: (String) -> Commit? = { null }

    @Test
    fun `a commit with one parent shows it, without a number`() {
        val parent = commit(hash(1))
        val links = parentLinks(commit(hash(2), parents = listOf(parent.hash)), isStash = false) { parent.takeIf { _ -> it == parent.hash } }

        assertEquals(1, links.shown.size)
        assertTrue(links.hidden.isEmpty())
        assertFalse(links.isNumbered)
        assertFalse(links.isRoot)
        assertEquals(parent.hash, links.shown.single().hash)
        assertEquals(1, links.shown.single().position)
    }

    @Test
    fun `a merge shows both parents in git's order, numbered`() {
        val first = commit(hash(1))
        val second = commit(hash(2))
        val merge = commit(hash(3), parents = listOf(first.hash, second.hash))

        val links = parentLinks(merge, isStash = false) { mapOf(first.hash to first, second.hash to second)[it] }

        assertEquals(listOf(first.hash, second.hash), links.shown.map { it.hash })
        assertEquals(listOf(1, 2), links.shown.map { it.position })
        assertTrue(links.isNumbered)
        assertTrue(links.hidden.isEmpty())
    }

    @Test
    fun `a root commit has no parents to show, and says so`() {
        val links = parentLinks(commit(hash(1)), isStash = false, loaded = nothingLoaded)

        assertTrue(links.isRoot)
        assertTrue(links.shown.isEmpty())
        assertTrue(links.hidden.isEmpty())
        assertFalse(links.isNumbered)
    }

    @Test
    fun `a parent is loaded when the log has it, and its subject is the message's first line`() {
        val parent = commit(hash(1), message = "Fix the parser\n\nIt dropped the last token.")

        val links = parentLinks(commit(hash(2), parents = listOf(parent.hash)), isStash = false) { parent }
        val link = links.shown.single()

        assertTrue(link.isLoaded)
        assertSame(parent, link.commit)
        assertEquals("Fix the parser", link.subject)
        assertEquals(parent.hash.take(7), link.shortHash)
    }

    @Test
    fun `a parent that the log hasn't loaded still shows, with its hash and no subject`() {
        val links = parentLinks(commit(hash(2), parents = listOf(hash(1))), isStash = false, loaded = nothingLoaded)
        val link = links.shown.single()

        assertFalse(link.isLoaded)
        assertNull(link.commit)
        assertNull(link.subject)
        assertEquals(hash(1), link.hash)
    }

    @Test
    fun `a parent with an empty message has no subject, so the row shows only the hash`() {
        val parent = commit(hash(1), message = "")

        val links = parentLinks(commit(hash(2), parents = listOf(parent.hash)), isStash = false) { parent }

        assertTrue(links.shown.single().isLoaded)
        assertNull(links.shown.single().subject)
    }

    @Test
    fun `a stash shows only the commit it was made on, not its index and untracked commits`() {
        val madeOn = commit(hash(1))
        val index = commit(hash(2))
        val untracked = commit(hash(3))
        val stash = commit(hash(4), parents = listOf(madeOn.hash, index.hash, untracked.hash))

        val links = parentLinks(stash, isStash = true) { mapOf(madeOn.hash to madeOn)[it] }

        assertEquals(listOf(madeOn.hash), links.shown.map { it.hash })
        assertTrue(links.hidden.isEmpty())
        assertFalse(links.isNumbered)
    }

    @Test
    fun `a stash made on nothing is a root`() {
        assertTrue(parentLinks(commit(hash(1)), isStash = true, loaded = nothingLoaded).isRoot)
    }

    @Test
    fun `a commit with four parents shows them all, and one with five collapses to three`() {
        val four = parentLinks(commit(hash(9), parents = (1..4).map(::hash)), isStash = false, loaded = nothingLoaded)
        val five = parentLinks(commit(hash(9), parents = (1..5).map(::hash)), isStash = false, loaded = nothingLoaded)

        assertEquals(4, four.shown.size)
        assertTrue(four.hidden.isEmpty())
        assertEquals(COLLAPSED_PARENT_ROWS, five.shown.size)
        assertEquals(listOf(4, 5), five.hidden.map { it.position })
        assertEquals((1..5).map(::hash), five.all.map { it.hash })
        assertTrue(five.isNumbered)
    }

    @Test
    fun `an octopus merge keeps every parent in order, the first three shown`() {
        val links = parentLinks(commit(hash(99), parents = (1..66).map(::hash)), isStash = false, loaded = nothingLoaded)

        assertEquals((1..3).map(::hash), links.shown.map { it.hash })
        assertEquals((4..66).map(::hash), links.hidden.map { it.hash })
        assertEquals((1..66).toList(), links.all.map { it.position })
    }

    @Test
    fun `hidden parents are loaded or not, one by one`() {
        val loaded = commit(hash(5))

        val links = parentLinks(commit(hash(9), parents = (1..6).map(::hash)), isStash = false) {
            loaded.takeIf { _ -> it == hash(5) }
        }

        assertEquals(listOf(false, true, false), links.hidden.map { it.isLoaded })
    }

    @Test
    fun `blank hashes and repeated ones leave no row that could not be selected`() {
        val links = parentLinks(
            commit(hash(5), parents = listOf(hash(1), "", " ", hash(1), hash(2))),
            isStash = false,
            loaded = nothingLoaded,
        )

        assertEquals(listOf(hash(1), hash(2)), links.shown.map { it.hash })
        assertEquals(listOf(1, 2), links.shown.map { it.position })
    }

    @Test
    fun `the lookup is asked only for the parents that are listed`() {
        val asked = mutableListOf<String>()

        parentLinks(commit(hash(9), parents = (1..6).map(::hash)), isStash = true) {
            asked += it
            null
        }

        assertEquals(listOf(hash(1)), asked)
    }
}
