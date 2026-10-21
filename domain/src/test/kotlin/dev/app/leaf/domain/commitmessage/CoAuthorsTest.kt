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

private val MAYA = CoAuthor("Maya Rodriguez", "maya@acme.dev")
private val MARCO = CoAuthor("Marco Bianchi", "marco@acme.dev")
private val PRIYA = CoAuthor("Priya Natarajan", "priya@acme.dev")

private fun commit(author: Identity, message: String = "A change", committer: Identity = author) =
    Commit("0".repeat(40), message, committer, author, 0L, emptyList())

private fun person(coAuthor: CoAuthor) = Identity(coAuthor.name, coAuthor.email)

class CoAuthorsTest {
    // --- Whom to suggest

    @Test
    fun `suggests the log's authors newest first, once per email, with the name they had last`() {
        val commits = listOf(
            commit(person(MAYA)),
            commit(person(MARCO)),
            commit(Identity("Maya R.", "MAYA@acme.dev")),
            commit(person(PRIYA)),
        )

        assertEquals(listOf(MAYA, MARCO, PRIYA), coAuthorCandidates(commits, you = null))
    }

    @Test
    fun `leaves out you, committers, and people without a name or an email`() {
        val you = Identity("Ann Lee", "Ann@acme.dev")
        val commits = listOf(
            commit(Identity("Ann Lee", "ann@acme.dev")),
            commit(person(MAYA), committer = Identity("GitHub", "noreply@github.com")),
            commit(Identity(null, "nameless@acme.dev")),
            commit(Identity("No Email", "  ")),
        )

        assertEquals(listOf(MAYA), coAuthorCandidates(commits, you))
    }

    @Test
    fun `suggests the co-authors that commit messages name, after the commit's author`() {
        val message = "Pair on the parser\n\nCo-authored-by: Marco Bianchi <marco@acme.dev>\nco-authored-by:Priya Natarajan<priya@acme.dev>"
        val commits = listOf(commit(person(MAYA), message))

        assertEquals(listOf(MAYA, MARCO, PRIYA), coAuthorCandidates(commits, you = null))
    }

    @Test
    fun `reads co-authors from the lines that name someone, whatever the key's case`() {
        val message = "S\n\nCO-AUTHORED-BY: Maya Rodriguez <maya@acme.dev>\nCo-authored-by: \nCo-authored-by: Bad <no email>\n" +
            "  Co-authored-by: Indented <in@acme.dev>"

        assertEquals(listOf(MAYA), coAuthorsIn(message))
    }

    // --- The line under the caret

    @Test
    fun `finds the Co-authored-by line that the caret is on, past its colon`() {
        val text = "Fix the parser\n\nco-authored-by:  ma\nSigned-off-by: Ann"
        val start = text.indexOf("co-authored-by")
        val end = text.indexOf("\nSigned")

        val line = coAuthorLineAt(text, end)!!
        assertEquals(start, line.lineStart)
        assertEquals(start + "co-authored-by:".length, line.keyEnd)
        assertEquals(end, line.lineEnd)
        assertEquals("ma", line.value)
        assertFalse(line.isComplete)

        assertEquals(line, coAuthorLineAt(text, line.keyEnd))
        assertNull(coAuthorLineAt(text, line.keyEnd - 1), "The caret inside the key")
        assertNull(coAuthorLineAt(text, text.length), "Another line")
        assertNull(coAuthorLineAt(text, 3))
        assertNull(coAuthorLineAt(text, -1))
        assertNull(coAuthorLineAt("Co-authored-by", 14), "No colon")
    }

    @Test
    fun `a line that names someone is complete`() {
        val text = "Co-authored-by: Maya Rodriguez <maya@acme.dev>  "

        assertTrue(coAuthorLineAt(text, text.length)!!.isComplete)
    }

    @Test
    fun `suggests whom the line's text matches, by name or email, and not who is named already`() {
        val candidates = listOf(MAYA, MARCO, PRIYA)

        fun choices(text: String) = coAuthorChoices(text, coAuthorLineAt(text, text.length)!!, candidates)

        assertEquals(listOf(MAYA, MARCO, PRIYA), choices("S\n\nCo-authored-by: "))
        assertEquals(listOf(MAYA, MARCO), choices("S\n\nCo-authored-by: MA"))
        assertEquals(listOf(PRIYA), choices("S\n\nCo-authored-by: priya@"))
        assertEquals(listOf(MARCO), choices("S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\nCo-authored-by: ma"))
        assertEquals(emptyList<CoAuthor>(), choices("S\n\nCo-authored-by: Someone <someone@acme.dev>"))
        assertEquals(emptyList<CoAuthor>(), choices("S\n\nCo-authored-by: nobody"))
    }

    @Test
    fun `picking fills the line under git's spelling of the key, and puts the caret at its end`() {
        val text = "S\n\nco-authored-by:  ma\nSigned-off-by: Ann <ann@acme.dev>"
        val line = coAuthorLineAt(text, text.indexOf("\nSigned"))!!

        val (filled, caret) = withCoAuthor(text, line, MAYA)

        assertEquals("S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\nSigned-off-by: Ann <ann@acme.dev>", filled)
        assertEquals(filled.indexOf("\nSigned"), caret)
    }

    @Test
    fun `Enter at the end of a line that names someone starts the next line with the key`() {
        val text = "S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>"

        val (continued, caret) = coAuthorEnter(text, text.length)!!

        assertEquals("$text\nCo-authored-by: ", continued)
        assertEquals(continued.length, caret)
        // Trailing spaces after the caret count as the end, and the lines below stay
        val withMore = "$text  \nSigned-off-by: Ann <ann@acme.dev>"
        assertEquals(
            "$text  \nCo-authored-by: \nSigned-off-by: Ann <ann@acme.dev>",
            coAuthorEnter(withMore, text.length)!!.first,
        )
    }

    @Test
    fun `Enter on a line that names no one ends the list, leaving the line empty`() {
        val text = "S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\nCo-authored-by: "

        assertEquals(
            "S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\n" to text.lastIndexOf("Co-authored-by"),
            coAuthorEnter(text, text.length),
        )
    }

    @Test
    fun `Enter elsewhere is a line feed`() {
        val text = "S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\nCo-authored-by: ma"

        assertNull(coAuthorEnter(text, text.length), "A line half typed")
        assertNull(coAuthorEnter(text, text.indexOf("Rodriguez")), "The middle of a line")
        assertNull(coAuthorEnter(text, 1), "Another line")
    }

    @Test
    fun `leaves out the Co-authored-by lines that name no one`() {
        assertEquals("S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\n", withoutEmptyCoAuthorLines(
            "S\n\nCo-authored-by: Maya Rodriguez <maya@acme.dev>\nCo-authored-by: \n  co-authored-by:\n"
        ))
        val untouched = "S\n\nCo-authored-by: ma"
        assertTrue(withoutEmptyCoAuthorLines(untouched) === untouched)
    }

    // --- Trailers without git

    @Test
    fun `tells trailer lines`() {
        assertTrue(isTrailerLine("Signed-off-by: Ann Lee <ann@acme.dev>"))
        assertTrue(isTrailerLine("Refs:#123"))
        assertFalse(isTrailerLine("Signed-off-by:"))
        assertFalse(isTrailerLine("Not a trailer: as it has spaces in the key"))
        assertFalse(isTrailerLine("-Dash: first"))
        assertFalse(isTrailerLine(""))
    }

    @Test
    fun `adds a trailer to a trailer block, or after a blank line, once`() {
        val trailer = "Co-authored-by: Maya Rodriguez <maya@acme.dev>"

        assertEquals("$trailer\n", appendTrailer("", trailer))
        assertEquals("Subject\n\n$trailer\n", appendTrailer("Subject\n", trailer))
        assertEquals("Subject: with a colon\n\n$trailer\n", appendTrailer("Subject: with a colon", trailer))
        assertEquals("S\n\nBody.\n\n$trailer\n", appendTrailer("S\n\nBody.", trailer))
        assertEquals("S\n\nSigned-off-by: Ann <a@b>\n$trailer\n", appendTrailer("S\n\nSigned-off-by: Ann <a@b>\n\n", trailer))
        assertEquals("S\n\n$trailer\n", appendTrailer("S\n\n$trailer", trailer))

        // As git commit -s: a sign-off that isn't the last trailer is added again
        val signOff = "Signed-off-by: Ann <a@b>"
        assertEquals(
            "S\n\n$signOff\n$trailer\n$signOff\n",
            appendTrailer("S\n\n$signOff\n$trailer", signOff, TrailerIfExists.ADD_IF_DIFFERENT_NEIGHBOR),
        )
        assertEquals("S\n\n$signOff\n", appendTrailer("S\n\n$signOff", signOff, TrailerIfExists.ADD_IF_DIFFERENT_NEIGHBOR))
    }
}
