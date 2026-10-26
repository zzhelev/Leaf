// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** A scanner reuses its patterns' last searches; it must answer as a scanner that searches each time. */
class ScannerSearchCacheTest {

    private val lib = JoniOnigLib()

    /** Patterns of the kinds grammars have: anchors, look-arounds, `\G`, matches that are empty. */
    private val patterns = listOf(
        "\\bfun\\b", "\\d+", "(?<=\\.)\\w+", "\\G\\s*", "\\G(\\w+)", "[a-z]+(?=\\()", "\"(?:[^\"\\\\]|\\\\.)*\"",
        "^\\s*#", "$", "\\b(?:if|else)\\b", "(?<!\\w)x", "\\\\G", "(?=x)", "\\b", "(?<=a)", "Ж+", "\\p{L}{2}",
        "(\\()|(\\))", "\\G(?=\\s)", "\\s+$", "\\A\\w",
    )

    private val alphabet = "ab x.()\"\\#Ж😀1 if else fun G\t".toList()

    private fun randomText(random: Random) =
        String(CharArray(random.nextInt(0, 60)) { alphabet[random.nextInt(alphabet.size)] })

    @Test
    fun `reused searches answer as new ones do`() {
        val random = Random(17)
        repeat(3_000) { round ->
            val chosen = patterns.shuffled(random).take(random.nextInt(1, 6))
            val cached = lib.createOnigScanner(chosen)
            val strings = List(2) { lib.createOnigString(randomText(random)) }
            var position = 0
            repeat(30) {
                val string = strings[random.nextInt(strings.size)]
                // Mostly onwards, as the tokenizer goes, sometimes back.
                position = if (random.nextInt(5) == 0) {
                    random.nextInt(0, string.length + 2)
                } else {
                    position + random.nextInt(0, 4)
                }
                val fresh = lib.createOnigScanner(chosen).findNextMatchSync(string, position)
                val message = "round $round: $chosen at $position in \"${string.content}\""
                assertEquals(fresh, cached.findNextMatchSync(string, position), message)
            }
        }
    }

    @Test
    fun `patterns with an unescaped G anchor are searched each time`() {
        assertTrue(JoniOnigScanner.hasGAnchor("\\G"))
        assertTrue(JoniOnigScanner.hasGAnchor("a|\\G(?=b)"))
        assertTrue(JoniOnigScanner.hasGAnchor("\\\\\\G"))
        assertFalse(JoniOnigScanner.hasGAnchor("\\\\G"))
        assertFalse(JoniOnigScanner.hasGAnchor("G"))
        assertFalse(JoniOnigScanner.hasGAnchor("\\"))
        assertFalse(JoniOnigScanner.hasGAnchor(""))
    }

    @Test
    fun `a G anchor matches where each search starts`() {
        val scanner = lib.createOnigScanner(listOf("\\G\\w"))
        val string = lib.createOnigString("ab cd")
        assertEquals(0, scanner.findNextMatchSync(string, 0)!!.captureIndices[0].start)
        assertEquals(1, scanner.findNextMatchSync(string, 1)!!.captureIndices[0].start)
        assertEquals(null, scanner.findNextMatchSync(string, 2))
        assertEquals(3, scanner.findNextMatchSync(string, 3)!!.captureIndices[0].start)
    }
}
