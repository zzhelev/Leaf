// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import dev.app.leaf.domain.models.ui.TextSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The text size's choices and steps (fork-only). */
class TextSizeTest {
    @Test
    fun `the default is the size Leaf has always had, and one of the choices`() {
        assertEquals(13, TextSize.DEFAULT)
        assertEquals(listOf(11, 12, 13, 14, 15, 16, 17, 18, 20), TextSize.CHOICES)
    }

    @Test
    fun `bigger and smaller step through the choices and stop at the ends`() {
        assertEquals(14, TextSize.bigger(13))
        assertEquals(20, TextSize.bigger(18))
        assertEquals(20, TextSize.bigger(20))
        assertEquals(12, TextSize.smaller(13))
        assertEquals(18, TextSize.smaller(20))
        assertEquals(11, TextSize.smaller(11))
    }

    @Test
    fun `stepping up from the smallest reaches every choice in order, and down again`() {
        val up = generateSequence(TextSize.CHOICES.first()) { size -> TextSize.bigger(size).takeIf { it != size } }
        val down = generateSequence(TextSize.CHOICES.last()) { size -> TextSize.smaller(size).takeIf { it != size } }

        assertEquals(TextSize.CHOICES, up.toList())
        assertEquals(TextSize.CHOICES.reversed(), down.toList())
    }

    @Test
    fun `a stored size that isn't a choice reads as the default`() {
        for (size in TextSize.CHOICES) {
            assertEquals(size, TextSize.of(size))
        }
        for (stored in listOf(null, 0, -3, 10, 19, 21, 72)) {
            assertEquals(TextSize.DEFAULT, TextSize.of(stored), "$stored")
        }
    }

    @Test
    fun `the font scale is the size against the default`() {
        assertEquals(1f, TextSize.scaleOf(13))
        assertEquals(20f / 13f, TextSize.scaleOf(20))
        assertEquals(11f / 13f, TextSize.scaleOf(11))
    }
}
