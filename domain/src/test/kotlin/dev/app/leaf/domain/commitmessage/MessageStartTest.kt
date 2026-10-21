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

private fun commits(vararg subjects: String, merges: Int = 0) =
    subjects.map { Commit("0".repeat(40), "$it\n", PERSON, PERSON, 0L, listOf("p")) } +
        List(merges) { Commit("1".repeat(40), "[OPS-1] Merge", PERSON, PERSON, 0L, listOf("p", "q")) }

private fun template(text: String) = CommitTemplate("/t", text)

class MessageStartTest {
    // --- The ticket key

    @Test
    fun `finds an uppercase key in the branch's name`() {
        assertEquals("PROJ-482", ticketKeyIn("refs/heads/feature/PROJ-482-login"))
        assertEquals("PROJ-482", ticketKeyIn("PROJ-482"))
        assertEquals("AB2-7", ticketKeyIn("fix/AB2-7_crash"))
        assertEquals("PROJ-1", ticketKeyIn("PROJ-1-and-OPS-2"))
    }

    @Test
    fun `no key in lowercase names, inside words, or without a number`() {
        assertNull(ticketKeyIn("refs/heads/hotfix-12"))
        assertNull(ticketKeyIn("feature/proj-482-login"))
        assertNull(ticketKeyIn("feature/XPROJ-482a"))
        assertNull(ticketKeyIn("feature/PROJ-login"))
        assertNull(ticketKeyIn("feature/P-12"))
        assertNull(ticketKeyIn("feature/PROJ-012"))
        assertNull(ticketKeyIn("main"))
    }

    @Test
    fun `the style that most subjects with a key use, colon when none do`() {
        assertEquals(TicketKeyStyle.COLON, ticketKeyStyle(commits("Fix the parser", "Add tags")))
        assertEquals(TicketKeyStyle.BRACKETS, ticketKeyStyle(commits("[OPS-1] a", "[OPS-2] b", "OPS-3: c")))
        assertEquals(TicketKeyStyle.SPACE, ticketKeyStyle(commits("OPS-1 a", "OPS-2 b", "[OPS-3] c")))
        assertEquals(TicketKeyStyle.COLON, ticketKeyStyle(commits("OPS-1: a", "[OPS-2] b")))
    }

    @Test
    fun `merge commits don't count, and only the newest subjects do`() {
        assertEquals(TicketKeyStyle.SPACE, ticketKeyStyle(commits("OPS-1 a", merges = 3)))

        val old = Array(CONVENTIONAL_SAMPLE_SIZE) { "Plain $it" } + Array(5) { "[OPS-$it] old" }
        assertEquals(TicketKeyStyle.COLON, ticketKeyStyle(commits(*old)))
    }

    @Test
    fun `each style's prefix`() {
        assertEquals("PROJ-1: ", TicketKeyStyle.COLON.prefix("PROJ-1"))
        assertEquals("[PROJ-1] ", TicketKeyStyle.BRACKETS.prefix("PROJ-1"))
        assertEquals("PROJ-1 ", TicketKeyStyle.SPACE.prefix("PROJ-1"))
    }

    // --- The start

    @Test
    fun `the key alone, with the caret after it`() {
        val start = messageStart("PROJ-1", "PROJ-1: ", null, MessageCleanup.DEFAULT)

        assertEquals(MessageStart("PROJ-1: ", 8, "PROJ-1", null), start)
    }

    @Test
    fun `nothing without a key or a template`() {
        assertNull(messageStart(null, null, null, MessageCleanup.DEFAULT))
        assertNull(messageStart(null, null, template(""), MessageCleanup.DEFAULT))
    }

    @Test
    fun `a template, with the caret at the end of its first line`() {
        val start = messageStart(null, null, template("Summary\n\n# Why?\n"), MessageCleanup.DEFAULT)

        assertEquals("Summary\n\n# Why?\n", start?.text)
        assertEquals(7, start?.caret)
    }

    @Test
    fun `a template that starts with a comment gets an empty line for the subject`() {
        val start = messageStart(null, null, template("# Why?\n"), MessageCleanup.DEFAULT)

        assertEquals("\n# Why?\n", start?.text)
        assertEquals(0, start?.caret)

        val semicolons = MessageCleanup(CleanupMode.STRIP, ";")
        assertEquals("# Why?\n", messageStart(null, null, template("# Why?\n"), semicolons)?.text)
    }

    @Test
    fun `the key goes on the template's first line`() {
        val blankFirst = messageStart("OPS-2", "[OPS-2] ", template("\n# Why?\n"), MessageCleanup.DEFAULT)
        assertEquals("[OPS-2] \n# Why?\n", blankFirst?.text)
        assertEquals(8, blankFirst?.caret)

        val commentFirst = messageStart("OPS-2", "OPS-2: ", template("# Why?\n"), MessageCleanup.DEFAULT)
        assertEquals("OPS-2: \n# Why?\n", commentFirst?.text)

        val textFirst = messageStart("OPS-2", "OPS-2: ", template("Summary\n"), MessageCleanup.DEFAULT)
        assertEquals("OPS-2: Summary\n", textFirst?.text)
        assertEquals(14, textFirst?.caret)
    }

    // --- Unedited

    @Test
    fun `the start committed as Tab wrote it is unedited`() {
        val keyOnly = messageStart("OPS-2", "OPS-2: ", null, MessageCleanup.DEFAULT)

        assertTrue(isUneditedStart("OPS-2: ", keyOnly, MessageCleanup.DEFAULT))
        assertTrue(isUneditedStart("OPS-2:\n\n# note\n", keyOnly, MessageCleanup.DEFAULT))
        assertFalse(isUneditedStart("OPS-2: Fix the parser", keyOnly, MessageCleanup.DEFAULT))
        assertFalse(isUneditedStart("", keyOnly, MessageCleanup.DEFAULT))
        assertFalse(isUneditedStart("OPS-2: ", null, MessageCleanup.DEFAULT))

        val withTemplate = messageStart("OPS-2", "OPS-2: ", template("\n# Why?\n"), MessageCleanup.DEFAULT)
        assertTrue(isUneditedStart("OPS-2: \n# Why?\n", withTemplate, MessageCleanup.DEFAULT))
        assertFalse(isUneditedStart("OPS-2: Fix\n# Why?\n", withTemplate, MessageCleanup.DEFAULT))
    }

    @Test
    fun `a template with text is unedited until something follows it`() {
        val start = messageStart(null, null, template("Summary\n\nDetails\n"), MessageCleanup.DEFAULT)

        assertTrue(isUneditedStart("Summary\n\nDetails\n", start, MessageCleanup.DEFAULT))
        assertTrue(isUneditedStart("Summary\n\nDetails\n\nSigned-off-by: Ann <a@b.c>\n", start, MessageCleanup.DEFAULT))
        assertFalse(isUneditedStart("Summary\n\nDetails\nand more\n", start, MessageCleanup.DEFAULT))
        assertFalse(isUneditedStart("Summary\n", start, MessageCleanup.DEFAULT))
        assertFalse(isUneditedStart("Summary\n\nDetails\n", start, MessageCleanup(CleanupMode.VERBATIM, "#")))
    }
}
