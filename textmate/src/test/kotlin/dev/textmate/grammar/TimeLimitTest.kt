// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.grammar

import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.regex.JoniOnigLib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The time limit of [Grammar.tokenizeLine], as vscode-textmate's `tokenizeLine(lineText, prevState, timeLimit)`. */
class TimeLimitTest {

    /** One match per character, so that a long line takes many steps of the tokenizer's loop. */
    private val grammar = GrammarReader.readGrammar(
        """{"scopeName": "source.steps", "patterns": [
            {"match": "a", "name": "letter.a"}, {"match": "b", "name": "letter.b"}]}"""
    ).let { Grammar(it.scopeName, it, JoniOnigLib()) }

    private val longLine = "ab".repeat(100_000)

    @Test
    fun `a line past the limit stops early, with the tokens up to where it stopped`() {
        val full = grammar.tokenizeLine(longLine)
        val stopped = grammar.tokenizeLine(longLine, timeLimit = 1)

        assertFalse(full.stoppedEarly)
        assertEquals(longLine.length, full.tokens.size)
        assertTrue(stopped.stoppedEarly)
        assertTrue(stopped.tokens.size < full.tokens.size, "${stopped.tokens.size} tokens")
        assertEquals(full.tokens.take(stopped.tokens.size), stopped.tokens)
    }

    @Test
    fun `a line within the limit is tokenized whole`() {
        val line = "ab".repeat(50)
        val result = grammar.tokenizeLine(line, timeLimit = 60_000)

        assertFalse(result.stoppedEarly)
        assertEquals(grammar.tokenizeLine(line).tokens, result.tokens)
    }

    @Test
    fun `no limit is the default, and 0 means none`() {
        val result = grammar.tokenizeLine(longLine, timeLimit = 0)

        assertFalse(result.stoppedEarly)
        assertEquals(longLine.length, result.tokens.size)
        assertEquals(listOf("source.steps", "letter.b"), result.tokens.last().scopes)
    }
}
