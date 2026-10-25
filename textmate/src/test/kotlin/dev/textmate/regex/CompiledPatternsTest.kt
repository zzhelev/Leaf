// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

import org.jcodings.specific.UTF8Encoding
import org.joni.Regex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class CompiledPatternsTest {

    private fun counting(compiles: AtomicInteger): (String) -> CompiledPattern = { pattern ->
        compiles.incrementAndGet()
        val bytes = pattern.toByteArray()
        CompiledPattern(Regex(bytes, 0, bytes.size, 0, UTF8Encoding.INSTANCE), refused = false)
    }

    @Test
    fun `a pattern is compiled once, and shared`() {
        val compiles = AtomicInteger()
        val pattern = "shared-${System.nanoTime()}"
        val first = CompiledPatterns.get(pattern, counting(compiles))
        assertSame(first, CompiledPatterns.get(pattern, counting(compiles)))
        assertEquals(1, compiles.get())
    }

    @Test
    fun `scanners that share a pattern match as before`() {
        val lib = JoniOnigLib()
        val one = lib.createOnigScanner(listOf("\\bfun\\b", "\\d+"))
        val other = JoniOnigLib().createOnigScanner(listOf("\\d+"))
        val string = lib.createOnigString("val fun = 42")
        assertEquals(4, one.findNextMatchSync(string, 0)!!.captureIndices[0].start)
        assertEquals(10, other.findNextMatchSync(string, 0)!!.captureIndices[0].start)
        assertEquals(10, one.findNextMatchSync(string, 5)!!.captureIndices[0].start)
    }

    @Test
    fun `a refused pattern is reported to each library that compiles it`() {
        val pattern = "(?<=a+)refused-${System.nanoTime()}"
        val first = JoniOnigLib()
        first.createOnigScanner(listOf(pattern))
        val second = JoniOnigLib()
        second.createOnigScanner(listOf(pattern, "\\d"))
        assertEquals(1, first.sentinelPatternCount)
        assertEquals(1, second.sentinelPatternCount)
        val scanner = second.createOnigScanner(listOf(pattern))
        assertEquals(null, scanner.findNextMatchSync(second.createOnigString("aaarefused"), 0))
    }

    @Test
    fun `the least recently used patterns are dropped past the limit`() {
        val compiles = AtomicInteger()
        val prefix = "bounded-${System.nanoTime()}-"
        val first = CompiledPatterns.get(prefix + 0, counting(compiles))
        for (n in 1..CompiledPatterns.MAX_PATTERNS) {
            CompiledPatterns.get(prefix + n, counting(compiles))
            // The first stays in use.
            if (n % 1_000 == 0) CompiledPatterns.get(prefix + 0, counting(compiles))
        }
        assertTrue(CompiledPatterns.size <= CompiledPatterns.MAX_PATTERNS, "${CompiledPatterns.size} kept")
        assertSame(first, CompiledPatterns.get(prefix + 0, counting(compiles)))
        val before = compiles.get()
        CompiledPatterns.get(prefix + 1, counting(compiles))
        assertEquals(before + 1, compiles.get(), "the oldest was kept")
    }
}
