// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleSide
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.styles.CLASSIC_LANES
import dev.app.leaf.theme.styles.MUTED_LANES
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val WIDTH = 60
private const val HEIGHT = 34

/** The graph draws its lanes in the style's colours (fork-only, `docs/fork/custom-styles.md`). */
class GraphLanesTest {
    private val author = Identity("Maya Chen", "maya.chen@example.com")

    /** On lane 1, with a child above it, a parent below, and lanes 0 and 2 passing by. */
    private val commit = GraphCommit(
        commit = Commit("a".repeat(40), "Subject", author, author, 0L, listOf("b".repeat(40))),
        lane = 1,
        passingLanes = listOf(0, 2),
        mergingLanes = emptyList(),
        forkingOffLanes = emptyList(),
        childCount = 1,
    )

    private val magentaLanes = StyleDefinition(
        id = "lanes",
        name = "Lanes",
        look = AccentStyle.TONAL,
        light = StyleSide(
            BuiltInStyles.templateSeeds(AccentStyle.TONAL, isLight = true),
            setByHand = mapOf(
                StyleSlot.LANE_1 to 0xFFFF00FF.toInt(),
                StyleSlot.LANE_2 to 0xFF00FFFF.toInt(),
                StyleSlot.LANE_3 to 0xFF00FF00.toInt(),
            ),
        ),
        dark = StyleSide(BuiltInStyles.templateSeeds(AccentStyle.TONAL, isLight = false)),
    )

    private fun render(style: StyleDefinition, content: @Composable () -> Unit): Bitmap {
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light, style = style) {
                Box(Modifier.width(WIDTH.dp).height(HEIGHT.dp).background(MaterialTheme.colors.background)) {
                    content()
                }
            }
        }

        try {
            return Bitmap.makeFromImage(scene.render())
        } finally {
            scene.close()
        }
    }

    private fun graph(style: StyleDefinition) = render(style) {
        CommitsGraph(
            modifier = Modifier.width(WIDTH.dp).height(HEIGHT.dp),
            plotCommit = commit,
            isStash = false,
            nodeColor = Color.Black,
            isSelected = false,
        )
    }

    private fun Bitmap.pixel(x: Int, y: Int) = getColor(x, y) or 0xFF000000.toInt()

    // A lane's line is 2 px wide, centred on (lane + 1) × 14: lane 0 at x = 14, lane 1 at 28, lane 2 at 42. Row 2 is
    // far from the node
    @Test
    fun `the graph draws each lane in the style's colour for it`() {
        val bitmap = graph(magentaLanes)

        assertEquals(0xFFFF00FF.toInt(), bitmap.pixel(13, 2))
        assertEquals(0xFF00FFFF.toInt(), bitmap.pixel(27, 2))
        assertEquals(0xFF00FF00.toInt(), bitmap.pixel(41, 2))
    }

    @Test
    fun `Tonal draws its muted lanes, and Classic Leaf's old ones`() {
        val tonal = graph(BuiltInStyles.TONAL)
        assertEquals(MUTED_LANES[0].toArgb(), tonal.pixel(13, 2))
        assertEquals(MUTED_LANES[1].toArgb(), tonal.pixel(27, 2))

        val classic = graph(BuiltInStyles.CLASSIC)
        assertEquals(CLASSIC_LANES[0].toArgb(), classic.pixel(13, 2))
        assertEquals(CLASSIC_LANES[1].toArgb(), classic.pixel(27, 2))
    }

    @Test
    fun `the uncommitted changes' node is in the first lane's colour`() {
        val bitmap = render(magentaLanes) {
            UncommittedChangesGraphNode(
                modifier = Modifier.width(WIDTH.dp).height(HEIGHT.dp),
                hasPreviousCommits = true,
                isSelected = false,
            )
        }

        assertEquals(0xFFFF00FF.toInt(), bitmap.pixel(14, HEIGHT / 2))
    }
}
