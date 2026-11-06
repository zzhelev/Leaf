// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The font settings' values, and which installed fonts the code font list shows (fork-only). */
class FontsTest {
    private val bundled = "Noto Sans Mono"

    @Test
    fun `an interface font is stored under its key, and a key this version doesn't know is no choice`() {
        assertEquals(InterfaceFont.INTER, InterfaceFont.fromKey("inter"))
        assertEquals(InterfaceFont.SYSTEM, InterfaceFont.fromKey("system"))
        assertNull(InterfaceFont.fromKey("comic-sans"))
        assertNull(InterfaceFont.fromKey(null))
        assertEquals(InterfaceFont.INTER, InterfaceFont.DEFAULT)
    }

    @Test
    fun `the defaults are Inter, the bundled code font and no ligatures`() {
        assertEquals(FontSettings(InterfaceFont.INTER, null, false), FontSettings())
    }

    @Test
    fun `a code font's name is trimmed, and a blank one stands for the bundled font`() {
        assertEquals("JetBrains Mono", codeFontName("  JetBrains Mono \t"))
        assertNull(codeFontName("   "))
        assertNull(codeFontName(""))
        assertNull(codeFontName(null))
    }

    @Test
    fun `the list is in natural order, ignoring case, each family once, and without the bundled font`() {
        val installed = listOf("menlo", "Courier New", "Fira Code 2", "Fira Code 10", "Menlo", " ", "noto sans mono", "Andale Mono")

        assertEquals(
            listOf("Andale Mono", "Courier New", "Fira Code 2", "Fira Code 10", "menlo"),
            codeFontChoices(installed, "", bundled).map { it.name },
        )
    }

    @Test
    fun `typing keeps the fonts whose name has the text, ignoring case and the spaces around it`() {
        val installed = listOf("JetBrains Mono", "JetBrains Mono NL", "Menlo", "Monaco", "PT Mono")

        assertEquals(
            listOf(
                CodeFontChoice("JetBrains Mono", 10, 4),
                CodeFontChoice("JetBrains Mono NL", 10, 4),
                CodeFontChoice("PT Mono", 3, 4),
            ),
            codeFontChoices(installed, "  MONO ", bundled),
        )
        assertEquals(listOf("Menlo", "Monaco"), codeFontChoices(installed, "m", bundled).map { it.name }.filter { it.startsWith("M") })
        assertEquals(emptyList<CodeFontChoice>(), codeFontChoices(installed, "consolas", bundled))
    }

    @Test
    fun `with nothing typed, every name matches from its start for no letters`() {
        assertEquals(listOf(CodeFontChoice("Menlo", 0, 0)), codeFontChoices(listOf("Menlo"), "", bundled))
    }

    @Test
    fun `the bundled font's row shows while its name has the text`() {
        assertTrue(bundledFontShows(bundled, ""))
        assertTrue(bundledFontShows(bundled, " noto "))
        assertTrue(bundledFontShows(bundled, "Mono"))
        assertFalse(bundledFontShows(bundled, "menlo"))
    }
}
