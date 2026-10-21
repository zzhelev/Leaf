// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

private fun wrap(text: String, cleanup: MessageCleanup = MessageCleanup.DEFAULT) = wrapBody(text, cleanup)

private val LONG = "The branch field only listed branches, so a tag had to be typed by hand, and a typo only showed " +
    "once git failed to find it."

class BodyWrapTest {
    @Test
    fun `wraps a long paragraph at 72 columns between words`() {
        assertEquals(
            """
            Make the clone dialog list tags

            The branch field only listed branches, so a tag had to be typed by hand,
            and a typo only showed once git failed to find it.
            """.trimIndent(),
            wrap("Make the clone dialog list tags\n\n$LONG"),
        )
    }

    @Test
    fun `joins the lines of a paragraph that has a long one, so no short line is left after it`() {
        val text = "Subject\n\n$LONG\nIt now lists them.\nShort."

        assertEquals(
            """
            Subject

            The branch field only listed branches, so a tag had to be typed by hand,
            and a typo only showed once git failed to find it. It now lists them.
            Short.
            """.trimIndent(),
            wrap(text),
        )
    }

    @Test
    fun `leaves paragraphs whose lines fit as they are`() {
        val text = "Subject\n\nShort lines\nas typed.\n\n$LONG"
        val wrapped = wrap(text)

        assertTrue(wrapped.startsWith("Subject\n\nShort lines\nas typed.\n\nThe branch"))
    }

    @Test
    fun `leaves the subject, and a subject that runs on, as they are`() {
        val subject = "A subject far longer than seventy-two characters that the subject hint is there for"

        assertEquals("$subject\n", wrap("$subject\n"))
        assertEquals("$subject\n$LONG", wrap("$subject\n$LONG"))
        assertFalse(canWrapBody(subject, MessageCleanup.DEFAULT))
    }

    @Test
    fun `leaves comment lines, a trailer block and what follows the scissors line`() {
        val longComment = "# $LONG"
        val trailers = "Co-authored-by: A Person With A Very Long Name Indeed <a.person.with.a.long.name@example.com>"
        val text = "Subject\n\n$longComment\n\n$trailers\nSigned-off-by: Ann <ann@acme.dev>\n"

        assertEquals(text, wrap(text))

        val scissors = MessageCleanup(CleanupMode.SCISSORS, "#")
        val withScissors = "Subject\n\nBody.\n# $SCISSORS_LINE\n$LONG\n"
        assertEquals(withScissors, wrap(withScissors, scissors))
    }

    @Test
    fun `a last paragraph that isn't all trailers is wrapped`() {
        val text = "Subject\n\nFixes: the parser\n$LONG"

        assertTrue(wrap(text).lines().all { it.length <= BODY_WRAP_COLUMN })
    }

    @Test
    fun `leaves indented lines and fenced code as they are`() {
        val code = "    val x = listOf(\"$LONG\")"
        val fenced = "```\n$LONG\n```"
        val text = "Subject\n\nBefore:\n\n$code\n\n$fenced\n"

        assertEquals(text, wrap(text))
    }

    @Test
    fun `wraps a list item with its next lines lined up after the marker`() {
        val text = "Subject\n\n- $LONG\n- Short item\n1. $LONG\n"

        assertEquals(
            """
            Subject

            - The branch field only listed branches, so a tag had to be typed by
              hand, and a typo only showed once git failed to find it.
            - Short item
            1. The branch field only listed branches, so a tag had to be typed by
               hand, and a typo only showed once git failed to find it.

            """.trimIndent(),
            wrap(text),
        )
    }

    @Test
    fun `a list item takes its lined-up and its lazy lines along`() {
        val text = "Subject\n\n- $LONG\n  lined up\nlazy\n"

        assertEquals(
            """
            Subject

            - The branch field only listed branches, so a tag had to be typed by
              hand, and a typo only showed once git failed to find it. lined up lazy

            """.trimIndent(),
            wrap(text),
        )
    }

    @Test
    fun `never starts a line with a word that would make it a comment, a list item or a fence`() {
        val text = "Subject\n\nThe parser dropped the last token of a line that ended in a quote, fixes #123 - and " +
            "it needs ``` quoting\n"
        val wrapped = wrap(text)

        // "#123" and "-" stay on the line before, past 72
        assertEquals(
            "Subject\n\nThe parser dropped the last token of a line that ended in a quote, fixes #123 -\n" +
                "and it needs ``` quoting\n",
            wrapped,
        )
        assertTrue(wrapped.lines().none { it.startsWith("#") || it.startsWith("- ") })
        assertEquals(wrapped, wrap(wrapped))
    }

    @Test
    fun `a word longer than the column gets a line of its own and isn't broken`() {
        val url = "https://example.com/" + "a".repeat(80)
        val text = "Subject\n\nSee $url for the details of the change.\n"

        assertEquals("Subject\n\nSee\n$url\nfor the details of the change.\n", wrap(text))
    }

    @Test
    fun `nothing to wrap when every line fits or a long word is already alone`() {
        assertFalse(canWrapBody("Subject\n\nShort body.\n", MessageCleanup.DEFAULT))
        assertFalse(canWrapBody("", MessageCleanup.DEFAULT))
        assertFalse(canWrapBody("# only a comment $LONG", MessageCleanup.DEFAULT))
        assertFalse(canWrapBody("Subject\n\n" + "x".repeat(90) + "\n", MessageCleanup.DEFAULT))
        assertTrue(canWrapBody("Subject\n\n$LONG", MessageCleanup.DEFAULT))
    }

    @Test
    fun `counts characters, not UTF-16 units`() {
        val emoji = "🚀"
        val fits = List(20) { "a$emoji" }.joinToString(" ")

        // 20 words of 2 characters and 19 spaces: 59 characters, in 79 UTF-16 units. The paragraph fits, so its lines
        // stay as typed rather than being joined
        assertEquals("Subject\n\n$fits\nend.", wrap("Subject\n\n$fits\nend."))

        val long = List(30) { "a$emoji" }.joinToString(" ")
        assertEquals(listOf(71, 17), wrap("Subject\n\n$long").lines().drop(2).map { it.codePointCount(0, it.length) })
    }

    @Test
    fun `the comment prefix comes from the repository's settings`() {
        val semicolons = MessageCleanup(CleanupMode.STRIP, ";")
        val text = "Subject\n\n; $LONG\n"

        assertEquals(text, wrap(text, semicolons))
        assertTrue(canWrapBody("Subject\n\n# $LONG\n", semicolons))
    }

    @Test
    fun `the caret stays before the same character`() {
        val before = "Subject\n\n$LONG"
        val after = wrap(before)
        val typo = before.indexOf("typo")

        assertEquals(after.indexOf("typo"), offsetAfterWrap(before, after, typo))
        assertEquals(after.length, offsetAfterWrap(before, after, before.length))
        assertEquals(0, offsetAfterWrap(before, after, 0))
    }

    @Test
    fun `random messages keep their words, wrap only to fit and wrap once`() {
        val random = Random(72)
        val words = listOf("a", "the", "branch", "worktree", "https://example.com/very/long/path/to/a/page/that/goes/on")

        repeat(2_000) {
            val text = buildString {
                append("Subject line ${random.nextInt()}\n")

                repeat(random.nextInt(0, 6)) {
                    append(if (random.nextBoolean()) "\n" else "")
                    val prefix = when (random.nextInt(6)) {
                        0 -> "- "
                        1 -> "# "
                        2 -> "    "
                        3 -> "12. "
                        else -> ""
                    }
                    append(prefix)
                    append(List(random.nextInt(1, 40)) { words.random(random) }.joinToString(" "))
                    append('\n')
                }
            }
            val wrapped = wrap(text)

            assertEquals(text.filterNot(Char::isWhitespace), wrapped.filterNot(Char::isWhitespace), text)
            assertEquals(wrapped, wrap(wrapped), text)
            assertEquals(text.lineSequence().first(), wrapped.lineSequence().first(), text)

            val before = text.lines().toSet()

            for (line in wrapped.lines()) {
                if (line !in before) {
                    val words = line.trim().split(' ').filter(String::isNotEmpty)
                    val listMarker = words.firstOrNull()?.let { it == "-" || it == "12." } == true
                    assertTrue(
                        line.length <= BODY_WRAP_COLUMN || words.size == 1 || (listMarker && words.size == 2),
                        "$line\nin\n$wrapped",
                    )
                }
            }
        }
    }
}
