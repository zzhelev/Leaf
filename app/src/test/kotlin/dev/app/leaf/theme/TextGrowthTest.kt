// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.TextSize
import dev.app.leaf.ui.ExtendedMenuButton
import dev.app.leaf.ui.MenuButton
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a bigger text size adds to the heights and widths that hold text (fork-only). */
class TextGrowthTest {
    @Test
    fun `nothing at the default size or below, and a line's growth above it`() {
        assertEquals(0.dp, textGrowth(1f))
        assertEquals(0.dp, textGrowth(TextSize.scaleOf(11)))
        assertEquals(((20f / 13f) - 1f) * 13f * 1.25f, textGrowth(TextSize.scaleOf(20)).value, 0.001f)
        assertEquals(textGrowth(1.5f, 11.sp) * 2, textGrowth(1.5f, 11.sp, lines = 2))
    }

    @Test
    fun `the rows keep their spacing at the default size and grow by a line's growth above it`() {
        for (type in LinesHeightType.entries) {
            val atDefault = linesHeight(type, TextSize.DEFAULT)
            assertEquals(linesHeight(type, 11).fileHeight, atDefault.fileHeight, "$type")

            for (size in listOf(16, 20)) {
                val bigger = linesHeight(type, size)
                val growth = textGrowth(TextSize.scaleOf(size))

                assertEquals(atDefault.fileHeight + growth, bigger.fileHeight, "$type at $size")
                assertEquals(atDefault.logCommitHeight + growth, bigger.logCommitHeight, "$type at $size")
                assertEquals(atDefault.sidePanelItemHeight + growth, bigger.sidePanelItemHeight, "$type at $size")
                assertEquals(atDefault.refChipIconPadding, bigger.refChipIconPadding, "$type at $size")
            }
        }

        assertEquals(32.dp, linesHeight(LinesHeightType.COMPACT, TextSize.DEFAULT).fileHeight)
    }

    @Test
    fun `a toolbar button is 56 dp wide at the default size, and widens for its label at a bigger one`() {
        for (label in listOf("Terminal", "Pop stash", "Settings")) {
            val atDefault = render(TextSize.DEFAULT, label) { MenuButton(label) }
            assertEquals(56, atDefault.width, label)
            assertTrue(atDefault.isWhole, "$label is cut at the default size")

            val bigger = render(20, label) { MenuButton(label) }
            assertTrue(bigger.isWhole, "$label is cut at 20")
            assertTrue(bigger.width > atDefault.width, "$label: ${bigger.width}")
        }
    }

    @Test
    fun `a toolbar button with a menu is 64 dp wide at the default size, and widens for its label at a bigger one`() {
        val atDefault = render(TextSize.DEFAULT, "Stash") { ExtendedMenuButton("Stash") }
        assertEquals(64, atDefault.width)

        val bigger = render(20, "A longer label") { ExtendedMenuButton("A longer label") }
        assertTrue(bigger.isWhole)
        assertTrue(bigger.width > 64, "${bigger.width}")
    }

    @Composable
    private fun MenuButton(label: String) = MenuButton(
        title = label,
        icon = ColorPainter(Color.Gray),
        keybinding = null,
        tooltip = label,
        onClick = {},
    )

    @Composable
    private fun ExtendedMenuButton(label: String) = ExtendedMenuButton(
        title = label,
        tooltipText = label,
        icon = ColorPainter(Color.Gray),
        keybinding = null,
        onClick = {},
        extendedListItems = emptyList(),
    )

    private fun linesHeight(type: LinesHeightType, size: Int): LinesHeight {
        var height: LinesHeight? = null

        ImageComposeScene(100, 100, Density(1f, TextSize.scaleOf(size))) {
            AppTheme(linesHeightType = type) { height = MaterialTheme.linesHeight }
        }.use { it.render() }

        return height!!
    }

    private class Rendered(val width: Int, val isWhole: Boolean)

    /** Renders [content] at a density of 1 and the text [size], and measures it and its [label]. */
    private fun render(size: Int, label: String, content: @Composable () -> Unit): Rendered {
        var width = 0

        return ImageComposeScene(600, 200, Density(1f, TextSize.scaleOf(size))) {
            AppTheme {
                Box(Modifier.onSizeChanged { width = it.width }) { content() }
            }
        }.use { scene ->
            scene.render()
            val text = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }.first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            }
            val results = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()

            Rendered(width, isWhole = layout.size.width >= layout.multiParagraph.maxIntrinsicWidth - 0.5f)
        }
    }
}
