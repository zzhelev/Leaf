// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.window.linux.LinuxFrameSupport
import dev.app.leaf.ui.window.linux.LinuxTitleBarRow
import dev.app.leaf.viewmodels.emptySettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.pow

private const val TITLE = "Check the other worktrees for changes"
private const val SUBTITLE = "How often Leaf looks for files changed in the other worktrees"

/** WCAG's threshold for text. */
private const val TEXT_CONTRAST = 4.5

/**
 * Settings' subtitles are in the style's secondary text colour, which reads at 4.5 : 1 or more on the dialog's surface
 * in every built-in style, and the titles keep the text colour (fork-only, item 13 of the upstream review of November
 * 2026). Every row's subtitle is drawn by [FieldTitles], so the rows of the Appearance page and the Linux title bar's are
 * checked through it too.
 */
class SettingsSubtitlesTest {
    /** The colours of the scene's theme, as its composition read them. */
    private class Palette(val surface: Color, val onBackground: Color, val onBackgroundSecondary: Color)

    private var scene: ImageComposeScene? = null
    private var colors: Palette? = null

    @AfterEach
    fun close() {
        scene?.close()
    }

    @Test
    fun `a subtitle is in the secondary text colour and reads on the surface, in every built-in style and theme`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (style in BuiltInStyles.ALL) {
                for (theme in listOf(Theme.Light, Theme.Dark)) {
                    val case = "${style.id} ${theme.name.lowercase()}"
                    show(style, theme) { FieldTitles(TITLE, SUBTITLE) }

                    val colors = colors!!
                    val surface = colors.surface.toArgb()
                    val subtitle = ink(SUBTITLE, surface)

                    assertSameColour(colors.onBackgroundSecondary.toArgb(), subtitle, "$case: the subtitle")
                    assertSameColour(colors.onBackground.toArgb(), ink(TITLE, surface), "$case: the title")

                    val contrast = contrast(subtitle, surface)
                    assertTrue(contrast >= TEXT_CONTRAST, "$case: the subtitle has $contrast : 1 on the surface")
                }
            }
        }

    @Test
    fun `a greyed-out row dims its subtitle as it dims its title`(): Unit = runBlocking(Dispatchers.Swing) {
        for (theme in listOf(Theme.Light, Theme.Dark)) {
            show(BuiltInStyles.TONAL, theme) { FieldTitles(TITLE, SUBTITLE, enabled = false) }

            val colors = colors!!
            val surface = colors.surface
            val dimmed = colors.onBackgroundSecondary.copy(alpha = 0.6f).compositeOver(surface).toArgb()

            assertSameColour(dimmed, ink(SUBTITLE, surface.toArgb()), "$theme: the greyed-out subtitle", SEE_THROUGH_TOLERANCE)
        }
    }

    @Test
    fun `the Appearance page's rows draw their subtitles in it`(): Unit = runBlocking(Dispatchers.Swing) {
        show(BuiltInStyles.TONAL, Theme.Light, height = 2600) {
            Box(Modifier.width(860.dp).padding(horizontal = 16.dp)) {
                Column { AppearanceRows(emptySettingsState()) {} }
            }
        }

        val colors = colors!!
        val surface = colors.surface.toArgb()

        for (subtitle in listOf(
            "Select the UI theme between light and dark mode. System follows your operating system and switches with it.",
            "The colours of buttons, notices, backgrounds and text, for the light and the dark theme.",
            "Row height in the log, the file lists and the branches pane",
            "The size of everything: text, icons and spacing",
            "The size of the text only. ${textSizeShortcuts()}",
            "The font of everything but code",
            "Diffs, blame, the conflict resolver, commit hashes and git commands",
            "Show the names of the toolbar's buttons next to their icons. They still hide in a narrow window.",
        )) {
            assertSameColour(colors.onBackgroundSecondary.toArgb(), ink(subtitle, surface), subtitle)
        }
    }

    @Test
    fun `the Linux title bar's row draws its subtitle in it, and greys it out where it isn't available`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val available = "Leaf draws the window's frame, with the tabs in its title bar and its own window buttons, " +
                "as on macOS and Windows."

            show(BuiltInStyles.TONAL, Theme.Dark) {
                LinuxTitleBarRow(isOn = false, support = LinuxFrameSupport.Supported, isDrawnNow = false) {}
            }
            assertSameColour(colors!!.onBackgroundSecondary.toArgb(), ink(available, colors!!.surface.toArgb()), "available")

            show(BuiltInStyles.TONAL, Theme.Dark) {
                LinuxTitleBarRow(
                    isOn = false,
                    support = LinuxFrameSupport.Unsupported(LinuxFrameSupport.Reason.WSL),
                    isDrawnNow = false,
                ) {}
            }
            val colors = colors!!
            val dimmed = colors.onBackgroundSecondary.copy(alpha = 0.6f).compositeOver(colors.surface).toArgb()
            assertSameColour(dimmed, ink("Not available under WSL.", colors.surface.toArgb()), "not available", SEE_THROUGH_TOLERANCE)
        }

    /** Renders [content] at twice the size, so that the glyphs' stems have pixels that the text covers whole. */
    private suspend fun show(
        style: StyleDefinition,
        theme: Theme,
        height: Int = 200,
        content: @Composable () -> Unit,
    ) {
        scene?.close()
        scene = ImageComposeScene(width = 1800, height = height * 2, density = Density(2f)) {
            AppTheme(selectedTheme = theme, style = style) {
                colors = Palette(
                    surface = MaterialTheme.colors.surface,
                    onBackground = MaterialTheme.colors.onBackground,
                    onBackgroundSecondary = MaterialTheme.colors.onBackgroundSecondary,
                )

                Box(Modifier.background(MaterialTheme.colors.surface)) { content() }
            }
        }

        repeat(3) {
            delay(30)
            scene!!.render()
        }
    }

    /** The pixel of [text] that is farthest from [background], which is the colour that it's drawn in. */
    private fun ink(text: String, background: Int): Int {
        val bounds = nodes().first { node -> text in node.texts() }.boundsInRoot
        val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
        var best = background
        var bestContrast = 0.0

        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val pixel = bitmap.getColor(x, y)
                val c = contrast(pixel, background)

                if (c > bestContrast) {
                    bestContrast = c
                    best = pixel
                }
            }
        }

        return best
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}

/**
 * Skia blends see-through text a little differently from `compositeOver`: 3 apart in a channel for the dark theme's
 * greyed-out subtitle. The text colour at 60% would be about 30 apart.
 */
private const val SEE_THROUGH_TOLERANCE = 4

/** Equal but for rounding: Solarized's text and secondary text are only 7 apart in each channel. */
private fun assertSameColour(expected: Int, actual: Int, what: String, tolerance: Int = 2) {
    val apart = listOf(16, 8, 0).maxOf { shift -> abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) }

    assertTrue(apart <= tolerance, "$what is ${hex(actual)}, not ${hex(expected)}")
}

private fun hex(argb: Int) = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

private fun luminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = (argb shr shift and 0xFF) / 255.0

        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private fun contrast(first: Int, second: Int): Double {
    val a = luminance(first)
    val b = luminance(second)

    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
