// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.app.leaf.domain.DiffMatchPatch
import dev.app.leaf.domain.models.MatchLine
import dev.app.leaf.ui.diff.syntax_highlighter.getSyntaxHighlighterFromExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FormatDiffTest {
    private val keyword = Color.Blue
    private val muted = Color.Gray

    private fun format(text: String, hiddenCharactersText: String? = null): AnnotatedString = formatDiff(
        line = MatchLine(listOf(DiffMatchPatch.Diff(DiffMatchPatch.Operation.EQUAL, text))),
        commentColor = Color.Green,
        keywordColor = keyword,
        annotationColor = Color.Magenta,
        contentAddedColor = Color.Cyan,
        contentRemovedColor = Color.Red,
        syntaxHighlighter = getSyntaxHighlighterFromExtension("kt"),
        hiddenCharactersText = hiddenCharactersText,
        mutedColor = muted,
    )

    @Test
    fun `a short line is shown whole and highlighted`() {
        val formatted = format("val x = 1")

        assertEquals("val x = 1", formatted.text)
        assertEquals(listOf("val"), formatted.spanStyles.filter { it.item.color == keyword }.map { formatted.text.substring(it.start, it.end) })
    }

    @Test
    fun `a long line stops after the limit, with how much is left out`() {
        val line = "val x = 1 ".repeat(5_000) // 50,000 characters

        val formatted = format(line, hiddenCharactersText = "40,000 more characters")

        assertEquals(line.take(MAX_DIFF_LINE_CHARACTERS) + " … 40,000 more characters", formatted.text)
        val marker = formatted.spanStyles.single { it.item.color == muted }
        assertEquals(MAX_DIFF_LINE_CHARACTERS, marker.start)
    }

    @Test
    fun `only the start of a long line is highlighted`() {
        val formatted = format("val x = 1 ".repeat(500)) // 5,000 characters

        val keywordSpans = formatted.spanStyles.filter { it.item.color == keyword }

        assertEquals(5_000, formatted.text.length)
        assertTrue(keywordSpans.isNotEmpty())
        assertTrue(keywordSpans.all { it.end <= MAX_HIGHLIGHTED_LINE_CHARACTERS }, "$keywordSpans")
    }
}
