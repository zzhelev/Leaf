// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff.syntax_highlighter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyntaxHighlighterTest {
    private val comment = Color.Green
    private val keyword = Color.Blue
    private val annotation = Color.Magenta

    /** The words of [line] that [highlighter] colors, with their color. */
    private fun coloredWords(highlighter: SyntaxHighlighter, line: String): List<Pair<String, Color>> =
        highlighter.syntaxHighlight(AnnotatedString(line), comment, keyword, annotation)
            .spanStyles
            .map { line.substring(it.start, it.end) to it.item.color }

    @Test
    fun `python comments start with a hash`() {
        val python = getSyntaxHighlighterFromExtension("py")

        assertEquals(listOf("    # if not done" to comment), coloredWords(python, "    # if not done"))
        assertEquals(listOf("if" to keyword, "not" to keyword), coloredWords(python, "if not done:"))
    }

    @Test
    fun `sql keywords are found in either case`() {
        val sql = getSyntaxHighlighterFromExtension("sql")

        assertEquals(
            listOf("select" to keyword, "from" to keyword),
            coloredWords(sql, "select name from users"),
        )
        assertEquals(listOf("SELECT" to keyword, "FROM" to keyword), coloredWords(sql, "SELECT name FROM users"))
    }

    @Test
    fun `each language has one highlighter, shared by every line`() {
        assertSame(getSyntaxHighlighterFromExtension("kt"), getSyntaxHighlighterFromExtension("kts"))
        assertSame(getSyntaxHighlighterFromExtension("txt"), getSyntaxHighlighterFromExtension(null))
        assertTrue(coloredWords(getSyntaxHighlighterFromExtension("txt"), "if not done").isEmpty())
    }
}
