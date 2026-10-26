// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.app.leaf.textmate

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Long lines, as minified files have, take time in proportion to their length, not its square. */
class LongLinesTest {

    /** One line of minified JavaScript with [statements] functions, about 60 characters each. */
    private fun minified(statements: Int, prefix: String = "") =
        prefix + (1..statements).joinToString("") { "var a$it=function(b,c){if(b>c){return\"x\"+b}return c*2};" }

    private fun millisToTokenize(line: String): Long {
        val grammar = ShippedGrammars.loadGrammar(ShippedGrammars.newRegistry(), "source.js")!!
        grammar.tokenizeLine(minified(50))
        val start = System.nanoTime()
        grammar.tokenizeLine(line)
        return (System.nanoTime() - start) / 1_000_000
    }

    /**
     * Each search ran to the line's end, and each offset was found by encoding the line up to it: this line took
     * 204 s, and 280 s with a Cyrillic letter in it, where it takes about half a second (2026-10-23).
     */
    @Test
    fun `a minified line of 60,000 characters takes seconds, not minutes`() {
        val ascii = millisToTokenize(minified(1_000))
        val unicode = millisToTokenize(minified(1_000, prefix = "var s=\"Ж\";"))
        assertTrue(ascii < 5_000, "$ascii ms")
        assertTrue(unicode < 5_000, "$unicode ms")
    }
}
