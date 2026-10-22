// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme.styles

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSide
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.graphLanes
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.styleColors
import dev.app.leaf.theme.styled
import dev.app.leaf.theme.tertiarySurface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What `AppTheme` gives the app from a style (fork-only): its scheme, roles and lanes, and only to what it holds. */
class AppThemeStylesTest {
    private val solarizedLike = StyleDefinition(
        id = "test",
        name = "Test",
        look = AccentStyle.TONAL,
        light = StyleSide(
            BuiltInStyles.templateSeeds(AccentStyle.TONAL, isLight = true)
                .with(StyleSeed.BACKGROUND, 0xFFFDF6E3.toInt())
                .with(StyleSeed.ACCENT, 0xFF268BD2.toInt()),
            setByHand = mapOf(StyleSlot.LANE_1 to 0xFFFF00FF.toInt(), StyleSlot.TEXT to 0xFF586E75.toInt()),
        ),
        dark = StyleSide(BuiltInStyles.templateSeeds(AccentStyle.TONAL, isLight = false)),
    )

    private fun <T> read(content: @androidx.compose.runtime.Composable () -> T): T {
        var value: T? = null
        val scene = ImageComposeScene(width = 10, height = 10, density = Density(1f)) { value = content() }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Test
    fun `a style's light side gives the app its scheme, roles and lanes`() {
        val resolved = resolveStyleSide(solarizedLike, isLight = true)

        val (scheme, roles, lanes) = read {
            var drawn: Triple<Any, Any, List<Color>>? = null
            AppTheme(selectedTheme = Theme.Light, style = solarizedLike) {
                drawn = Triple(
                    listOf(MaterialTheme.colors.background, MaterialTheme.colors.onBackground, MaterialTheme.colors.tertiarySurface),
                    MaterialTheme.styleColors,
                    MaterialTheme.graphLanes,
                )
            }
            checkNotNull(drawn)
        }

        assertEquals(
            listOf(Color(0xFFFDF6E3), Color(0xFF586E75), resolved.palette[StyleSlot.HEADERS]),
            scheme,
        )
        assertEquals(resolved.palette.toStyleColors(), roles)
        assertEquals(Color(0xFFFF00FF), lanes.first())
        assertEquals(MUTED_LANES.drop(1), lanes.drop(1))
    }

    @Test
    fun `the dark side is drawn when the theme is dark, and System follows the system`() {
        val dark = read {
            var background: Color? = null
            AppTheme(selectedTheme = Theme.System, style = solarizedLike, systemIsDark = true) {
                background = MaterialTheme.colors.background
            }
            checkNotNull(background)
        }

        assertEquals(darkTheme.background, dark)
    }

    @Test
    fun `an AppTheme inside another colours only what it holds`() {
        val (before, inner, after) = read {
            var seen = Triple(Color.Unspecified, Color.Unspecified, Color.Unspecified)
            AppTheme(selectedTheme = Theme.Light, style = BuiltInStyles.CLASSIC) {
                val outerBefore = MaterialTheme.colors.backgroundSelected
                var innerColor = Color.Unspecified
                AppTheme(selectedTheme = Theme.Dark, style = BuiltInStyles.QUIET) {
                    innerColor = MaterialTheme.colors.backgroundSelected
                }
                seen = Triple(outerBefore, innerColor, MaterialTheme.colors.backgroundSelected)
            }
            seen
        }

        assertEquals(lightTheme.backgroundSelected, before)
        assertEquals(darkTheme.styled(AccentStyle.QUIET).backgroundSelected, inner)
        assertEquals(lightTheme.backgroundSelected, after)
    }

    @Test
    fun `outside any AppTheme, the lanes are Leaf's own`() {
        assertEquals(CLASSIC_LANES, read { MaterialTheme.graphLanes })
    }
}
