// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** What `AppTheme` gives the app in each built-in style (fork-only): the roles and `primary`. */
class AppThemeStyleTest {
    private data class Drawn(
        val roles: StyleColors,
        val primary: Color,
        val primaryVariant: Color,
        val background: Color,
        val conflictFile: Color,
    )

    private class Harness(theme: Theme, style: AccentStyle, systemIsDark: Boolean = false) {
        var theme by mutableStateOf(theme)
        var style by mutableStateOf(style)
        var drawn: Drawn? = null

        val scene = ImageComposeScene(width = 40, height = 20, density = Density(1f)) {
            AppTheme(
                selectedTheme = this@Harness.theme,
                style = BuiltInStyles.ofLook(this@Harness.style),
                systemIsDark = systemIsDark,
            ) {
                drawn = Drawn(
                    roles = MaterialTheme.styleColors,
                    primary = MaterialTheme.colors.primary,
                    primaryVariant = MaterialTheme.colors.primaryVariant,
                    background = MaterialTheme.colors.background,
                    conflictFile = MaterialTheme.colors.conflictFile,
                )
            }
        }

        fun frame(): Drawn {
            scene.render()
            return checkNotNull(drawn)
        }
    }

    private fun <T> drawn(
        theme: Theme,
        style: AccentStyle,
        systemIsDark: Boolean = false,
        test: Harness.() -> T,
    ): T {
        val harness = Harness(theme, style, systemIsDark)

        try {
            return harness.test()
        } finally {
            harness.scene.close()
        }
    }

    @Test
    fun `without a style, AppTheme is tonal, as a new install is`() {
        var roles: StyleColors? = null
        val scene = ImageComposeScene(width = 40, height = 20, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) { roles = MaterialTheme.styleColors }
        }
        scene.render()
        scene.close()

        assertEquals(styleColorsFor(AccentStyle.TONAL, lightTheme.styled(AccentStyle.TONAL)), roles)
    }

    @Test
    fun `each style gives its roles in the light and the dark theme`() {
        for (style in AccentStyle.entries) {
            for ((theme, scheme) in listOf(Theme.Light to lightTheme, Theme.Dark to darkTheme)) {
                drawn(theme, style) {
                    assertEquals(styleColorsFor(style, scheme.styled(style)), frame().roles, "$style $theme")
                    // The rest of the palette is the theme's
                    assertEquals(scheme.background, frame().background, "$style $theme")
                    assertEquals(scheme.conflictingFile, frame().conflictFile, "$style $theme: the conflict amber stays")
                }
            }
        }
    }

    @Test
    fun `Tonal and Quiet mute primary, and the light theme's primaryVariant, which is the same blue`() {
        for (style in listOf(AccentStyle.TONAL, AccentStyle.QUIET)) {
            drawn(Theme.Light, style) {
                assertEquals(MUTED_PRIMARY_LIGHT, frame().primary, "$style light")
                assertEquals(MUTED_PRIMARY_LIGHT, frame().primaryVariant, "$style light")
                assertEquals(MUTED_PRIMARY_LIGHT, frame().roles.mainAction.background, "$style light")
            }
            drawn(Theme.Dark, style) {
                assertEquals(MUTED_PRIMARY_DARK, frame().primary, "$style dark")
                // A pale blue for text on the dark background, which the muted blue doesn't hold
                assertEquals(darkTheme.primaryVariant, frame().primaryVariant, "$style dark")
                assertEquals(MUTED_PRIMARY_DARK, frame().roles.mainAction.background, "$style dark")
            }
        }

        assertEquals(Color(0xFF376AAA), MUTED_PRIMARY_LIGHT)
        assertEquals(Color(0xFF3464A0), MUTED_PRIMARY_DARK)
    }

    @Test
    fun `Classic keeps the theme's own primary`() {
        drawn(Theme.Light, AccentStyle.CLASSIC) {
            assertEquals(lightTheme.primary, frame().primary)
            assertEquals(lightTheme.primaryVariant, frame().primaryVariant)
        }
        drawn(Theme.Dark, AccentStyle.CLASSIC) {
            assertEquals(darkTheme.primary, frame().primary)
            assertEquals(darkTheme.primaryVariant, frame().primaryVariant)
        }
    }

    @Test
    fun `a change of style in the settings shows at once, and so does a change of theme`() {
        drawn(Theme.Light, AccentStyle.CLASSIC) {
            assertEquals(lightTheme.primary, frame().primary)

            style = AccentStyle.QUIET
            assertEquals(styleColorsFor(AccentStyle.QUIET, lightTheme.styled(AccentStyle.QUIET)), frame().roles)

            theme = Theme.Dark
            assertEquals(styleColorsFor(AccentStyle.QUIET, darkTheme.styled(AccentStyle.QUIET)), frame().roles)

            style = AccentStyle.CLASSIC
            assertEquals(classicStyleColors(darkTheme), frame().roles)
        }
    }

    @Test
    fun `the System theme takes the style of the system's light or dark setting`() {
        drawn(Theme.System, AccentStyle.TONAL, systemIsDark = true) {
            assertEquals(MUTED_PRIMARY_DARK, frame().primary)
            assertEquals(styleColorsFor(AccentStyle.TONAL, darkTheme.styled(AccentStyle.TONAL)), frame().roles)
        }
        drawn(Theme.System, AccentStyle.TONAL, systemIsDark = false) {
            assertEquals(MUTED_PRIMARY_LIGHT, frame().primary)
        }
    }

    @Test
    fun `outside AppTheme, the roles come from the Material colours, laid out as Classic`() {
        val colors = lightColors(primary = Color(0xFF123456), error = Color(0xFF654321))
        var roles: StyleColors? = null
        val scene = ImageComposeScene(width = 40, height = 20, density = Density(1f)) {
            MaterialTheme(colors = colors) { roles = MaterialTheme.styleColors }
        }
        scene.render()
        scene.close()

        assertEquals(RoleColors(colors.primary, colors.onPrimary), roles!!.mainAction)
        assertEquals(RoleColors(colors.error, colors.onError), roles!!.neutral)
    }

    @Test
    fun `the styles differ from each other and from Classic in both themes`() {
        for (scheme in listOf(lightTheme, darkTheme)) {
            val tonal = styleColorsFor(AccentStyle.TONAL, scheme.styled(AccentStyle.TONAL))
            val quiet = styleColorsFor(AccentStyle.QUIET, scheme.styled(AccentStyle.QUIET))
            val classic = styleColorsFor(AccentStyle.CLASSIC, scheme)

            assertNotEquals(tonal, quiet)
            assertNotEquals(tonal, classic)
            assertNotEquals(quiet, classic)
        }
    }

    @Test
    fun `the tonal and quiet values are the board's`() {
        val tonalLight = styleColorsFor(AccentStyle.TONAL, lightTheme.styled(AccentStyle.TONAL))
        val tonalDark = styleColorsFor(AccentStyle.TONAL, darkTheme.styled(AccentStyle.TONAL))
        val quietLight = styleColorsFor(AccentStyle.QUIET, lightTheme.styled(AccentStyle.QUIET))
        val quietDark = styleColorsFor(AccentStyle.QUIET, darkTheme.styled(AccentStyle.QUIET))

        assertEquals(RoleColors(Color(0xFFDDEBFE), Color(0xFF255691), Color(0x47255691)), tonalLight.action)
        assertEquals(RoleColors(Color.White, Color(0xFF212934), Color(0x2E000000)), tonalLight.neutral)
        assertEquals(RoleColors(Color(0xFFFCE1DF), Color(0xFF963635), Color(0x47963635)), tonalLight.destructive)
        assertEquals(
            StripColors(
                Color(0xFFFEEFCF), Color(0xFF643D17), Color(0xFFC68128), Color(0xFFEDC889),
                RoleColors(Color(0xFFF7D499), Color(0xFF573312), Color(0x40573312)),
            ),
            tonalLight.warningStrip,
        )
        assertEquals(Color(0xFFDBF3DB), tonalLight.successStrip.background)
        assertEquals(Color(0xFF245427), tonalLight.successStrip.content)
        assertEquals(RoleColors(Color(0xFFB6E2B6), Color(0xFF214E24), Color(0x40214E24)), tonalLight.successStrip.button)
        assertEquals(Color(0xFFC68128), tonalLight.warningIcon)

        assertEquals(RoleColors(Color(0xFF23364F), Color(0xFFAFD1FC), Color(0x3DAFD1FC)), tonalDark.action)
        assertEquals(RoleColors(Color(0x14FFFFFF), Color.White, Color(0x2EFFFFFF)), tonalDark.neutral)
        assertEquals(RoleColors(Color(0xFF4C2B2A), Color(0xFFF7B3B0), Color(0x3DF7B3B0)), tonalDark.destructive)
        assertEquals(Color(0xFF3E2D15), tonalDark.warningStrip.background)
        assertEquals(Color(0xFFF0DCB1), tonalDark.warningStrip.content)
        assertEquals(Color(0xFF6C4F25), tonalDark.warningStrip.border)
        assertEquals(RoleColors(Color(0xFF65461D), Color(0xFFFAEED1), Color(0x33FAEED1)), tonalDark.warningStrip.button)
        assertEquals(Color(0xFF213622), tonalDark.successStrip.background)
        assertEquals(Color(0xFFE6B463), tonalDark.warningIcon)

        assertEquals(quietLight.neutral, quietLight.action)
        assertEquals(RoleColors(Color.White, Color(0xFFA43B3A), Color(0x2E000000)), quietLight.destructive)
        assertEquals(Color(0xFFFDF2DD), quietLight.warningStrip.background)
        assertEquals(Color(0xFF262F38), quietLight.warningStrip.content)
        assertEquals(quietLight.neutral, quietLight.warningStrip.button)
        assertEquals(Color(0xFFEAF8E9), quietLight.successStrip.background)

        assertEquals(quietDark.neutral, quietDark.action)
        assertEquals(Color(0xFFED8B86), quietDark.destructive.content)
        assertEquals(Color(0xFF2F2416), quietDark.warningStrip.background)
        assertEquals(Color(0xFF1D2A1D), quietDark.successStrip.background)

        for (roles in listOf(tonalLight, tonalDark, quietLight, quietDark)) {
            assertEquals(Color.White, roles.mainAction.content)
            assertEquals(Color.Transparent, roles.mainAction.border, "Only the main action is filled, without a line")
        }
    }
}
