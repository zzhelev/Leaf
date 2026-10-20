// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.avatars.AVATAR_PALETTE
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** WCAG's threshold for text, which the white initial on an avatar is. */
private const val TEXT_CONTRAST = 4.5

/** WCAG's threshold for shapes and other parts of an interface, which the fill against the plain row is. */
private const val SHAPE_CONTRAST = 3.0

/** Colours closer than this in CIELAB (the plain distance) read as one colour at a glance. The closest pair is 18.8. */
private const val MIN_DISTANCE = 18.0

/**
 * The avatar fills (fork-only, upstream issue 231): the bar that was stated when the palette was chosen. The initial is
 * white, so each fill must hold white at 4.5 : 1, and show against the plain row of the built-in themes at 3 : 1. A
 * custom theme can't break the first, as the fill doesn't depend on the theme, and can't be promised the second, as its
 * background is its own.
 */
class AvatarPaletteTest {
    private val colors = AVATAR_PALETTE.map { Color(it) }
    private val rows = mapOf("light" to lightTheme.background, "dark" to darkTheme.background)

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color) = 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)

        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun lab(color: Color): Triple<Double, Double, Double> {
        val r = linear(color.red)
        val g = linear(color.green)
        val b = linear(color.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116

        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun distance(first: Color, second: Color): Double {
        val (l1, a1, b1) = lab(first)
        val (l2, a2, b2) = lab(second)

        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    @Test
    fun `the contrast of black on white is what the formula says, 21 to 1`() {
        // The check below is only as good as this helper
        assertEquals(21.0, contrast(Color.Black, Color.White), 1e-9)
        assertEquals(1.0, contrast(Color.White, Color.White), 1e-9)
    }

    @Test
    fun `the white initial holds 4_5 to 1 on every fill`() {
        for (color in colors) {
            assertTrue(contrast(Color.White, color) >= TEXT_CONTRAST, "#${color.toHex()}: ${contrast(Color.White, color)}")
        }
    }

    @Test
    fun `every fill holds 3 to 1 against the plain row of the light and the dark theme`() {
        for ((theme, row) in rows) {
            for (color in colors) {
                assertTrue(contrast(color, row) >= SHAPE_CONTRAST, "$theme: #${color.toHex()}: ${contrast(color, row)}")
            }
        }
    }

    @Test
    fun `no two fills look alike`() {
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                val between = distance(colors[i], colors[j])

                assertTrue(between >= MIN_DISTANCE, "#${colors[i].toHex()} and #${colors[j].toHex()}: $between")
            }
        }
    }

    private fun Color.toHex() = (0xFFFFFF and toArgb()).toString(16).padStart(6, '0')
}
