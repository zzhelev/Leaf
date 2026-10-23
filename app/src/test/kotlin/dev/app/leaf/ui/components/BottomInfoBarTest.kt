// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.RoleColors
import dev.app.leaf.theme.styleColors
import dev.app.leaf.updates.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The bottom bar's update button (fork-only): the action role's fill and text in every built-in style and theme, so
 * that it can be read. Its text took the role's colour on a fill of `primary`, which was blue on blue (1.35 : 1 in
 * Tonal's light theme).
 */
class BottomInfoBarTest {
    private val update = Update(appVersion = "1.4.0", appCode = 9, downloadUrl = "https://example.com/leaf")
    private val buttonText = "Update 1.4.0 available"

    private fun contrast(a: Color, b: Color): Double {
        val lighter = maxOf(a.luminance(), b.luminance()) + 0.05
        val darker = minOf(a.luminance(), b.luminance()) + 0.05

        return lighter / darker
    }

    private fun isClose(expected: Color, actual: Color, tolerance: Int): Boolean {
        val a = expected.toArgb()
        val b = actual.toArgb()

        return listOf(16, 8, 0).all { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= tolerance }
    }

    @Test
    fun `the update button has the action role's colours, and readable text, in every style`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (style in BuiltInStyles.ALL) {
                for (theme in listOf(Theme.Light, Theme.Dark)) {
                    val case = "${style.name} $theme"
                    var surface = Color.Unspecified
                    var action = RoleColors(Color.Unspecified, Color.Unspecified)

                    val scene = ImageComposeScene(width = 1400, height = 64, density = Density(2f)) {
                        AppTheme(selectedTheme = theme, style = style) {
                            surface = MaterialTheme.colors.surface
                            action = MaterialTheme.styleColors.action
                            BottomInfoBar(newUpdate = update, onOpenUrlInBrowser = {})
                        }
                    }

                    try {
                        val bitmap = Bitmap.makeFromImage(scene.render(System.nanoTime()))
                        val button: Rect = scene.semanticsOwners
                            .flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                            .first { node ->
                                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == buttonText }
                            }
                            .boundsInRoot
                        val fill = action.background.compositeOver(surface)

                        fun pixel(x: Int, y: Int) = Color(bitmap.getColor(x, y))

                        // Past the 1 dp line inside the edge, before the 16 dp of padding that the text starts after
                        val atLeft = pixel(button.left.toInt() + 5, button.center.y.toInt())
                        assertTrue(isClose(fill, atLeft, tolerance = 2), "$case: fill $atLeft, expected $fill")

                        // The text's pixels, inside the line: the strongest is a glyph's core, in the role's text colour
                        val inside = (button.left.toInt() + 4 until button.right.toInt() - 4).flatMap { x ->
                            (button.top.toInt() + 4 until button.bottom.toInt() - 4).map { y -> pixel(x, y) }
                        }
                        val strongest = inside.maxBy { contrast(it, fill) }

                        assertTrue(
                            contrast(strongest, fill) >= 4.5,
                            "$case: text $strongest on $fill holds %.2f : 1".format(contrast(strongest, fill)),
                        )
                        assertTrue(
                            inside.any { isClose(action.content, it, tolerance = 8) },
                            "$case: no pixel of the text is the role's ${action.content}",
                        )
                    } finally {
                        scene.close()
                    }
                }
            }
        }
}
