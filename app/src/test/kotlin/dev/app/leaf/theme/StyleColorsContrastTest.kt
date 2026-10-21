// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.models.ui.AccentStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

/** WCAG's threshold for text: the labels of buttons and the text of strips and notices. */
private const val TEXT_CONTRAST = 4.5

/** WCAG's threshold for an icon that is all there is to tell what it means: a line's buttons, a conflicted file's icon. */
private const val ICON_CONTRAST = 3.0

/**
 * The roles of each style in the light and the dark theme (fork-only), against WCAG's thresholds, on the surfaces that
 * Leaf puts them on. A see-through fill counts as what it gives on that surface.
 *
 * Not checked, as something else on them says what they mean: the lines inside the edges of buttons and notices, and
 * the icons of strips and notices, which their text explains. The light themes' strip icon (#C68128 on #FEEFCF) holds
 * 2.8 : 1.
 */
class StyleColorsContrastTest {
    private data class Case(val style: AccentStyle, val themeName: String, val scheme: ColorsScheme) {
        val roles = styleColorsFor(style, scheme)
        override fun toString() = "$style $themeName"
    }

    private val cases = AccentStyle.entries.flatMap { style ->
        listOf("light" to lightTheme, "dark" to darkTheme).map { (name, theme) -> Case(style, name, theme.styled(style)) }
    }

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

    private fun Color.hex() = "#" + (0xFFFFFF and toArgb()).toString(16).padStart(6, '0')

    private enum class Button { MAIN_ACTION, ACTION, NEUTRAL, DESTRUCTIVE }

    private fun Case.colorsOf(button: Button) = when (button) {
        Button.MAIN_ACTION -> roles.mainAction
        Button.ACTION -> roles.action
        Button.NEUTRAL -> roles.neutral
        Button.DESTRUCTIVE -> roles.destructive
    }

    /** Where a button of each role is: a pane's header, a hunk's header, a file's row, a dialog, a strip. */
    private fun Case.surfacesOf(button: Button): Map<String, Color> {
        val common = mapOf("background" to scheme.background, "surface" to scheme.surface)

        return when (button) {
            Button.MAIN_ACTION -> common
            // Stage all and Unstage all in the lists' headers (tertiary), the hunk's and the file's buttons (secondary)
            Button.ACTION, Button.NEUTRAL -> common + mapOf(
                "tertiarySurface" to scheme.tertiarySurface,
                "secondarySurface" to scheme.secondarySurface,
            )
            // Discard hunk (secondary), Abort in the status pane and on the operation banner
            Button.DESTRUCTIVE -> common + mapOf(
                "secondarySurface" to scheme.secondarySurface,
                "warning strip" to roles.warningStrip.background,
            )
        }
    }

    private fun assertAll(failures: List<String>) {
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the contrast of black on white is what the formula says, 21 to 1`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 1e-9)
        assertEquals(1.0, contrast(Color.White, Color.White), 1e-9)
        // A see-through fill counts as what it gives on its surface
        assertEquals(Color.White.copy(alpha = 0.5f).compositeOver(Color.Black).red, 0.5f, 0.01f)
    }

    @Test
    fun `every button's label holds 4_5 to 1 on every surface the button is on`() {
        val failures = cases.flatMap { case ->
            Button.entries.flatMap { button ->
                val role = case.colorsOf(button)

                case.surfacesOf(button).mapNotNull { (surfaceName, surface) ->
                    val fill = role.background.compositeOver(surface)
                    val ratio = contrast(role.content, fill)

                    "$case $button on $surfaceName: ${role.content.hex()} on ${fill.hex()} is $ratio"
                        .takeIf { ratio < TEXT_CONTRAST }
                }
            }
        }

        assertAll(failures)
    }

    @Test
    fun `the text of strips and notices, and of their buttons, holds 4_5 to 1`() {
        val failures = cases.flatMap { case ->
            listOf("warning strip" to case.roles.warningStrip, "success strip" to case.roles.successStrip)
                .flatMap { (name, strip) ->
                    val button = strip.button.background.compositeOver(strip.background)

                    listOfNotNull(
                        "$case $name: ${contrast(strip.content, strip.background)}"
                            .takeIf { contrast(strip.content, strip.background) < TEXT_CONTRAST },
                        "$case $name's button: ${contrast(strip.button.content, button)}"
                            .takeIf { contrast(strip.button.content, button) < TEXT_CONTRAST },
                    )
                }
        }

        assertAll(failures)
    }

    @Test
    fun `a line's buttons, which have only an icon, hold 3 to 1 on an opaque fill`() {
        // DiffLineActions lays the fill on the theme's background, so that the line's tint doesn't show through
        val failures = cases.flatMap { case ->
            listOf("stage" to case.roles.action, "unstage" to case.roles.neutral, "discard" to case.roles.destructive)
                .mapNotNull { (name, role) ->
                    val ratio = contrast(role.content, role.background.compositeOver(case.scheme.background))

                    "$case $name: $ratio".takeIf { ratio < ICON_CONTRAST }
                }
        }

        assertAll(failures)
    }

    @Test
    fun `the warning icon holds 3 to 1 on the plain background in Tonal and Quiet, where Classic keeps its amber`() {
        for (case in cases) {
            val ratio = contrast(case.roles.warningIcon, case.scheme.background)

            if (case.style == AccentStyle.CLASSIC) {
                // Leaf's amber as it was: 1.75 : 1 on white, which the styles are there to fix
                assertEquals(case.scheme.conflictingFile, case.roles.warningIcon, "$case")
            } else {
                assertTrue(ratio >= ICON_CONTRAST, "$case: ${case.roles.warningIcon.hex()} is $ratio")
            }
        }
    }

    @Test
    fun `links and the tab underline hold 4_5 to 1 on the background, and toasts' text on primary`() {
        for (case in cases) {
            val link = contrast(case.scheme.primaryVariant, case.scheme.background)
            val toast = contrast(case.scheme.onPrimary, case.scheme.primary)

            assertTrue(link >= TEXT_CONTRAST, "$case links: ${case.scheme.primaryVariant.hex()} is $link")
            assertTrue(toast >= TEXT_CONTRAST, "$case toasts: $toast")
        }
    }
}
