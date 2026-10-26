// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EscapeRewriteTest {

    @Test
    fun `N and O outside a class become what Oniguruma 6_9 reads them as`() {
        assertEquals("a(?-m:.)c", EscapeRewrite.rewrite("""a\Nc"""))
        assertEquals("(?-m:.)*+(?m:.)", EscapeRewrite.rewrite("""\N*+\O"""))
        // Python's grammar marks each character of a regular expression's set with `\N`.
        assertEquals("(?-m:.)", EscapeRewrite.rewrite("""\N"""))
    }

    @Test
    fun `in a class, after an escaped backslash, and in other escapes, they stay`() {
        assertEquals("""[\N][^]\N][a[\O]][[:alpha:]\N]""", EscapeRewrite.rewrite("""[\N][^]\N][a[\O]][[:alpha:]\N]"""))
        assertEquals("""\\N\\\\O""", EscapeRewrite.rewrite("""\\N\\\\O"""))
        assertEquals("""[\]\N]x(?-m:.)""", EscapeRewrite.rewrite("""[\]\N]x\N"""))
        val untouched = """\p{N}\x{4E}\n"""
        assertSame(untouched, EscapeRewrite.rewrite(untouched))
    }

    @Test
    fun `the scanner matches as Oniguruma 6_9 does`() {
        val lib = JoniOnigLib()
        fun match(pattern: String, text: String): Pair<Int, Int>? =
            lib.createOnigScanner(listOf(pattern)).findNextMatchSync(lib.createOnigString(text), 0)
                ?.captureIndices?.first()?.let { it.start to it.end }

        // What vscode-oniguruma 2.0.1 gives for the same patterns and texts.
        assertEquals(0 to 1, match("""\N""", "ab"))
        assertEquals(4 to 7, match("""a\Nc""", "a\nc axc"))
        assertEquals(1 to 2, match("""[\N]""", "xN"))
        assertNotNull(match("""(?<=(?:a|bc))\N""", "bcd"))
        assertEquals(0, lib.sentinelPatternCount)
    }
}
