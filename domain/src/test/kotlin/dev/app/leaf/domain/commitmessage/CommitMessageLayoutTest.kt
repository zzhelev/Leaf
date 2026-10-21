// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val STRIP = MessageCleanup.DEFAULT
private val WHITESPACE = MessageCleanup(CleanupMode.WHITESPACE, "#")
private val VERBATIM = MessageCleanup(CleanupMode.VERBATIM, "#")
private val SCISSORS = MessageCleanup(CleanupMode.SCISSORS, "#")
private val AUTO = MessageCleanup(CleanupMode.STRIP, null)

/** The parts of [text] that [spans] cover. */
private fun parts(text: String, spans: List<MessageSpan>) = spans.map { text.substring(it.start, it.end) }

class CommitMessageLayoutTest {
    @Test
    fun `the subject is the first line that git keeps and that isn't blank, without its trailing white space`() {
        val text = "\n  \n# A comment\nFix the parser  \t\n\nBody\n"
        val layout = commitMessageLayout(text, STRIP)

        assertEquals("Fix the parser", text.substring(layout.subject!!.start, layout.subject!!.end))
        assertEquals(14, layout.subjectLength)
        assertNull(layout.subjectOverflow)
        assertNull(layout.blankLineAt)
        assertFalse(layout.isEmpty)
    }

    @Test
    fun `a comment line is the subject when git keeps comments`() {
        val text = "# Not a comment here\n"

        for (cleanup in listOf(WHITESPACE, VERBATIM, SCISSORS)) {
            val layout = commitMessageLayout(text, cleanup)

            assertEquals("# Not a comment here", text.substring(layout.subject!!.start, layout.subject!!.end))
            assertTrue(layout.droppedSpans.isEmpty())
        }
    }

    @Test
    fun `dims the lines that start with the comment prefix, only at their start`() {
        val text = "Subject\n# dropped\n  # kept, as git keeps it\n#\nBody # kept\n"

        assertEquals(listOf("# dropped", "#"), parts(text, commitMessageLayout(text, STRIP).droppedSpans))
        assertEquals(
            listOf("; dropped"),
            parts("S\n; dropped\n# kept\n", commitMessageLayout("S\n; dropped\n# kept\n", MessageCleanup(CleanupMode.STRIP, ";")).droppedSpans),
        )
        assertEquals(
            listOf("// dropped"),
            parts("S\n// dropped\n/ kept\n", commitMessageLayout("S\n// dropped\n/ kept\n", MessageCleanup(CleanupMode.STRIP, "//")).droppedSpans),
        )
    }

    @Test
    fun `scissors leave out everything from the scissors line on`() {
        val text = "Subject\n\n# ------------------------ >8 ------------------------\nBelow\n# more\n"
        val layout = commitMessageLayout(text, SCISSORS)

        assertEquals(
            listOf("# ------------------------ >8 ------------------------\nBelow\n# more\n"),
            parts(text, layout.droppedSpans),
        )
        assertEquals("Subject\n", cleanCommitMessage(text, SCISSORS))
    }

    @Test
    fun `a scissors line without a line feed after it, or not at a line's start, cuts nothing`() {
        for (text in listOf(
            "Subject\n# ------------------------ >8 ------------------------",
            "Subject\n # ------------------------ >8 ------------------------\nBody\n",
        )) {
            assertTrue(commitMessageLayout(text, SCISSORS).droppedSpans.isEmpty(), text)
        }
    }

    @Test
    fun `counts the subject's characters as code points, and tints what goes past 72`() {
        val subject = "😀".repeat(70) + "abcd"
        val text = "$subject\n\nBody\n"
        val layout = commitMessageLayout(text, STRIP)

        assertEquals(74, layout.subjectLength)
        assertEquals("cd", text.substring(layout.subjectOverflow!!.start, layout.subjectOverflow!!.end))
    }

    @Test
    fun `a subject of exactly 72 characters has no overflow`() {
        assertNull(commitMessageLayout("a".repeat(72), STRIP).subjectOverflow)
        assertEquals(1, commitMessageLayout("a".repeat(73), STRIP).subjectOverflow!!.let { it.end - it.start })
    }

    @Test
    fun `finds a subject that runs on into the next line, past comment lines`() {
        val text = "Subject\n# comment\nBody\n"
        val layout = commitMessageLayout(text, STRIP)

        assertEquals("Subject".length, layout.blankLineAt)
        assertEquals("Subject\n\n# comment\nBody\n", withBlankLineAfterSubject(text, layout))
        assertEquals("Subject\n\nBody\n", cleanCommitMessage(withBlankLineAfterSubject(text, layout), STRIP))
    }

    @Test
    fun `a subject followed by a blank line, or by nothing, stands alone`() {
        for (text in listOf("Subject", "Subject\n", "Subject\n\nBody", "Subject\n   \nBody", "Subject\n# only a comment\n")) {
            assertNull(commitMessageLayout(text, STRIP).blankLineAt, text)
        }
    }

    @Test
    fun `with comments kept, a comment line after the subject runs on`() {
        assertEquals(1, commitMessageLayout("S\n# kept\n", WHITESPACE).blankLineAt)
    }

    @Test
    fun `a message of blank lines and comments is empty, except verbatim`() {
        for (text in listOf("", "  \n\t\n", "# comment\n\n# another")) {
            assertTrue(commitMessageLayout(text, STRIP).isEmpty, text)
        }

        assertFalse(commitMessageLayout("# comment", WHITESPACE).isEmpty)
        assertTrue(commitMessageLayout("  \n", WHITESPACE).isEmpty)
        assertFalse(commitMessageLayout("  \n", VERBATIM).isEmpty)
        assertTrue(commitMessageLayout("", VERBATIM).isEmpty)
    }

    @Test
    fun `with auto, a trailing comment block is dropped and the same lines higher up stay`() {
        // As measured on git 2.54 (CommitMessageCleanupTest compares random messages with git's commits)
        assertEquals("#", MessageCleanup(CleanupMode.STRIP, null).commentPrefixFor("Subject\n# c\n"))
        assertEquals(";", AUTO.commentPrefixFor("S\n\nB\n# c\nmore\n"))
        assertEquals(";", AUTO.commentPrefixFor("#only\n\n"))
        assertEquals("@", AUTO.commentPrefixFor("# first\nSubject\n; semi\nmore\n"))
        assertEquals("#", AUTO.commentPrefixFor("S\n#x\nConflicts:\n\tfile\n"))
        assertNull(AUTO.commentPrefixFor("#a\n;b\n@c\n!d\n\$e\n%f\n^g\n&h\n|i\n:j\nk\n"))

        assertEquals(listOf("# c"), parts("Subject\n# c\n", commitMessageLayout("Subject\n# c\n", AUTO).droppedSpans))
        assertTrue(commitMessageLayout("S\n\nB\n# c\nmore\n", AUTO).droppedSpans.isEmpty())
    }

    @Test
    fun `cleans up as git's stripspace does`() {
        assertEquals("Subject\n\nBody\n", cleanCommitMessage("\n\nSubject  \n\n\n\nBody\t\r\n\n", STRIP))
        // A comment between blank lines leaves one blank line
        assertEquals("a\n\nb\n", cleanCommitMessage("a\n\n# c\n\nb", STRIP))
        // Only git's white space is trimmed: a no-break space stays
        assertEquals("a \n", cleanCommitMessage("a  \n", STRIP))
        assertEquals("  # kept\n", cleanCommitMessage("  # kept\n# dropped", STRIP))
        assertEquals("# kept\n", cleanCommitMessage("# kept", WHITESPACE))
        assertEquals("\n Verbatim  \n\n", cleanCommitMessage("\n Verbatim  \n\n", VERBATIM))
        assertEquals("", cleanCommitMessage("# c\n", STRIP))
    }
}
