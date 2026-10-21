// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.compare

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The A and B badges: their fills, and the outline that draws them on a light theme only. */
class CompareBadgeTest {
    @Test
    fun `a badge is its side's colour, outlined on a light theme only`() {
        for (letter in CompareLetter.entries) {
            val dark = render(letter, Theme.Dark)
            val light = render(letter, Theme.Light)

            // Inside, the fill; at the edge's middle, the outline on light only
            assertEquals(letter.fill.toArgb(), dark.getColor(3, 8), "$letter's fill, dark")
            assertEquals(letter.fill.toArgb(), light.getColor(3, 8), "$letter's fill, light")
            assertEquals(letter.fill.toArgb(), dark.getColor(0, 8), "$letter's edge, dark")
            assertEquals(letter.lightOutline.toArgb(), light.getColor(0, 8), "$letter's edge, light")
        }
    }

    @Test
    fun `the letter reads on either fill, and the outline shows on white and on a selected row`() {
        for (letter in CompareLetter.entries) {
            assertTrue(contrast(COMPARE_LETTER_COLOR, letter.fill) >= 4.5, "$letter's letter")
            assertTrue(contrast(letter.lightOutline, Color.White) >= 3.0, "$letter's outline on white")
            assertTrue(contrast(letter.lightOutline, Color(0xFFCEE1F2)) >= 3.0, "$letter's outline on a selected row")
        }
    }

    private fun render(letter: CompareLetter, theme: Theme): Bitmap {
        val scene = ImageComposeScene(width = 16, height = 16, density = Density(1f)) {
            AppTheme(theme) { CompareBadge(letter) }
        }

        return try {
            Bitmap.makeFromImage(scene.render())
        } finally {
            scene.close()
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()

        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double = if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)

        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
