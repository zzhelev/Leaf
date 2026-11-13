// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.find

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.CodeColours
import dev.app.leaf.domain.styles.StyleColorMath
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.styles.resolveStyleSide
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Find's fixed colours (upstream issues 275 and 312) against every built-in style's colours, with each choice of code
 * colours: code stays readable on them, and the current match's outline shows on the lines.
 */
class FindColorsTest {
    private fun contrast(first: Color, second: Color) = StyleColorMath.contrast(first.toArgb(), second.toArgb())

    private fun eachSide(check: (isLight: Boolean, colors: FindColors, palette: (StyleSlot) -> Color, name: String) -> Unit) {
        for (isLight in listOf(true, false)) {
            val colors = if (isLight) LIGHT_FIND_COLORS else DARK_FIND_COLORS

            for (style in BuiltInStyles.ALL) for (choice in CodeColours.entries) {
                val palette = resolveStyleSide(style, isLight, choice).palette

                check(isLight, colors, { palette[it] }, "${style.id} $choice light=$isLight")
            }
        }
    }

    @Test
    fun `text and code hold 4,5 to 1 on a match, as on the lines`() = eachSide { _, colors, palette, name ->
        for (slot in StyleSlot.SYNTAX + StyleSlot.TEXT) {
            val ratio = contrast(palette(slot), colors.match)

            assertTrue(ratio >= 4.5, "$name $slot: %.2f".format(ratio))
        }
    }

    @Test
    fun `text and code hold 3 to 1 on the current match, which its outline also marks`() = eachSide { _, colors, palette, name ->
        for (slot in StyleSlot.SYNTAX + StyleSlot.TEXT) {
            val ratio = contrast(palette(slot), colors.current)

            assertTrue(ratio >= 3.0, "$name $slot: %.2f".format(ratio))
        }
    }

    @Test
    fun `the current match's outline holds 3 to 1 on its fill and on the lines`() = eachSide { _, colors, palette, name ->
        assertTrue(contrast(colors.outline, colors.current) >= 3.0, "$name on the current match")

        for (slot in listOf(StyleSlot.BACKGROUND, StyleSlot.ADDED_LINE, StyleSlot.REMOVED_LINE)) {
            val ratio = contrast(colors.outline, palette(slot))

            assertTrue(ratio >= 3.0, "$name on $slot: %.2f".format(ratio))
        }
    }
}
