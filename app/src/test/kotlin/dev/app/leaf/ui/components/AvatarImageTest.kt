// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.avatars.AVATAR_PALETTE
import dev.app.leaf.domain.avatars.avatarPaletteIndex
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.styled
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

private const val SIZE = 40

/** The fill of an avatar without a picture (fork-only, upstream issue 231), read off the corner of a square avatar. */
@OptIn(ExperimentalComposeUiApi::class)
class AvatarImageTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    /** The ARGB of the avatar's top-left pixel, which the initial in the middle doesn't reach. */
    private fun fill(
        theme: Theme,
        person: Identity,
        color: Color? = null,
        style: AccentStyle = AccentStyle.DEFAULT,
    ): Int {
        scene?.close()
        scene = ImageComposeScene(width = SIZE, height = SIZE, density = Density(1f)) {
            AppTheme(theme, accentStyle = style) {
                Box(Modifier.size(SIZE.dp)) {
                    // The default avatar provider is None: no picture is asked for, so the fallback is what shows
                    AvatarImage(Modifier.fillMaxSize(), person, shape = RectangleShape, color = color)
                }
            }
        }

        scene!!.render(System.nanoTime())

        return org.jetbrains.skia.Bitmap.makeFromImage(scene!!.render(System.nanoTime())).getColor(2, 2)
    }

    private fun palette(person: Identity) = AVATAR_PALETTE[person.avatarPaletteIndex()!!]

    private val maya = Identity("Maya Chen", "maya.chen@example.com")

    /** A person whose colour isn't Maya's. */
    private val other = (0 until 100)
        .map { Identity("Other $it", "other$it@example.com") }
        .first { it.avatarPaletteIndex() != maya.avatarPaletteIndex() }

    @Test
    fun `a person's fill is their colour from the palette`() {
        assertEquals(palette(maya), fill(Theme.Dark, maya))
        assertEquals(palette(other), fill(Theme.Dark, other))
    }

    @Test
    fun `two people get different fills, one person the same wherever their name is spelt`() {
        assertNotEquals(fill(Theme.Dark, maya), fill(Theme.Dark, other))
        assertEquals(fill(Theme.Dark, maya), fill(Theme.Dark, Identity("M. CHEN", "  Maya.Chen@Example.COM ")))
    }

    @Test
    fun `the fill is the same in the light and the dark theme`() {
        assertEquals(fill(Theme.Light, maya), fill(Theme.Dark, maya))
    }

    @Test
    fun `a colour that is given wins, as the grey of the committer's badge`() {
        val grey = Color(0xFF808080)

        assertEquals(grey.toArgb(), fill(Theme.Dark, maya, color = grey))
        assertEquals(grey.toArgb(), fill(Theme.Light, maya, color = grey))
    }

    @Test
    fun `someone with no name and no email keeps the theme's primary, which follows the style`() {
        val nobody = Identity(name = null, email = null)

        for (style in AccentStyle.entries) {
            assertEquals(darkTheme.styled(style).primary.toArgb(), fill(Theme.Dark, nobody, style = style), "$style")
            assertEquals(lightTheme.styled(style).primary.toArgb(), fill(Theme.Light, nobody, style = style), "$style")
        }
    }

    @Test
    fun `a person's fill is the same in every style`() {
        for (style in AccentStyle.entries) {
            assertEquals(palette(maya), fill(Theme.Light, maya, style = style), "$style")
        }
    }

    @Test
    fun `an avatar with a rounded shape still fills with the person's colour`() {
        scene?.close()
        scene = ImageComposeScene(width = SIZE, height = SIZE, density = Density(1f)) {
            AppTheme(Theme.Dark) {
                Box(Modifier.size(SIZE.dp)) {
                    AvatarImage(Modifier.fillMaxSize(), maya, shape = RoundedCornerShape(50))
                }
            }
        }
        scene!!.render(System.nanoTime())

        // Just inside the circle, left of the initial
        val pixel = org.jetbrains.skia.Bitmap.makeFromImage(scene!!.render(System.nanoTime())).getColor(6, SIZE / 2)

        assertEquals(palette(maya), pixel)
    }
}
