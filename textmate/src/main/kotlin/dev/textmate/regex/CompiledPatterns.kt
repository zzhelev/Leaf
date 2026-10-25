// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

import org.joni.Regex

/** A pattern as Joni compiled it, and whether Joni refused it, so that [regex] never matches. */
internal class CompiledPattern(val regex: Regex, val refused: Boolean)

/**
 * The patterns that scanners compiled, shared by all of them, the least recently used dropped first past
 * [MAX_PATTERNS]. A grammar repeats its patterns in many rules, and each rule's scanner compiled its own: TypeScript's
 * grammar compiled 2,144 patterns for a short file, 266 of them different, and kept 41 MB. A search doesn't change a
 * compiled [Regex] (Joni only fills a table lazily for backward searches, which scanners don't make), so threads share
 * them as JRuby does.
 */
internal object CompiledPatterns {

    /**
     * Patterns kept: the shipped grammars have about 4,000 different ones. End patterns with back-references are
     * built from the text, so they're never all kept.
     */
    const val MAX_PATTERNS: Int = 10_000

    private val compiled = object : LinkedHashMap<String, CompiledPattern>(1_024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompiledPattern>): Boolean =
            size > MAX_PATTERNS
    }

    /** [pattern] compiled, by [compile] the first time. Two threads may both compile it; they get the same result. */
    fun get(pattern: String, compile: (String) -> CompiledPattern): CompiledPattern {
        synchronized(compiled) { compiled[pattern] }?.let { return it }
        val result = compile(pattern)
        synchronized(compiled) { compiled[pattern] = result }
        return result
    }

    /** How many patterns are kept. For tests. */
    val size: Int get() = synchronized(compiled) { compiled.size }
}
