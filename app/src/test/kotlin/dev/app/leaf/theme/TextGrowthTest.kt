// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.TextSize
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun linesHeight(type: LinesHeightType, size: Int): LinesHeight {
        var height: LinesHeight? = null

        ImageComposeScene(100, 100, Density(1f, TextSize.scaleOf(size))) {
            AppTheme(linesHeightType = type) { height = MaterialTheme.linesHeight }
        }.use { it.render() }

        return height!!
    }
}
