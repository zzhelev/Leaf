// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StyleIdsTest {
    @Test
    fun `an id is the name in lower case, with dashes between its words`() {
        assertEquals("solarized-dark", StyleIds.fromName("Solarized Dark"))
        assertEquals("teal", StyleIds.fromName("  Teal!! "))
        assertEquals("cafe-creme", StyleIds.fromName("Café Crème"))
        assertEquals("tonal-copy-2", StyleIds.fromName("Tonal copy (2)"))
        assertEquals("my-style", StyleIds.fromName("my_style"))
    }

    @Test
    fun `a name with nothing an id can hold gives style`() {
        for (name in listOf("", "   ", "---", "日本", "✓✓")) assertEquals("style", StyleIds.fromName(name), name)
    }

    @Test
    fun `a long name is cut to 40 characters, without a dash at the end`() {
        val id = StyleIds.fromName("A very long name for a style that someone made late at night")

        assertTrue(id.length <= StyleIds.MAX_LENGTH, id)
        assertEquals("a-very-long-name-for-a-style-that-someon", id)
        assertEquals("abcdefghijklmnopqrstuvwxyzabcdefghijklm", StyleIds.fromName("abcdefghijklmnopqrstuvwxyzabcdefghijklm-x"))
    }

    @Test
    fun `a taken id gets the first free number, and a built-in style's is always taken`() {
        assertEquals("lagoon", StyleIds.unique("lagoon", emptySet()))
        assertEquals("lagoon-2", StyleIds.unique("lagoon", setOf("lagoon")))
        assertEquals("lagoon-3", StyleIds.unique("lagoon", setOf("lagoon", "lagoon-2")))

        for (builtIn in BuiltInStyles.ALL) {
            assertEquals("${builtIn.id}-2", StyleIds.unique(builtIn.id, emptySet()))
        }
    }

    @Test
    fun `a numbered id stays within 40 characters`() {
        val long = "a".repeat(StyleIds.MAX_LENGTH)
        val taken = setOf(long) + (2..11).map { "a".repeat(StyleIds.MAX_LENGTH - it.toString().length - 1) + "-$it" }
        val id = StyleIds.unique(long, taken)

        assertEquals("a".repeat(37) + "-12", id)
        assertTrue(id.length <= StyleIds.MAX_LENGTH)
    }
}
