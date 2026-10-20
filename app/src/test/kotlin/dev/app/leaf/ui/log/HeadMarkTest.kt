// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val WIDTH = 60
private const val HEIGHT = 34
private val NODE_COLOR = Color(0xFF66BB6A)

/** The mark of HEAD's row (fork-only, upstream issue 255): a ring on the node in the graph, and a bold subject. */
@OptIn(ExperimentalComposeUiApi::class)
class HeadMarkTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private val author = Identity("Maya Chen", "maya.chen@example.com")

    /** A commit on the first lane: its node is centered 14 dp from the left (lane 0 is 7 dp in, and 14 dp wide). */
    private val commit = GraphCommit(
        commit = Commit("a".repeat(40), "Subject", author, author, 0L, emptyList()),
        lane = 0,
        passingLanes = emptyList(),
        mergingLanes = emptyList(),
        forkingOffLanes = emptyList(),
        childCount = 0,
    )

    private fun graph(theme: Theme, isHead: Boolean, isSelected: Boolean = false): Bitmap {
        scene?.close()
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            AppTheme(theme) {
                Box(Modifier.width(WIDTH.dp).height(HEIGHT.dp).background(MaterialTheme.colors.background)) {
                    CommitsGraph(
                        modifier = Modifier.width(WIDTH.dp).height(HEIGHT.dp),
                        plotCommit = commit,
                        isStash = false,
                        nodeColor = NODE_COLOR,
                        isSelected = isSelected,
                        isHead = isHead,
                    )
                }
            }
        }

        scene!!.render(System.nanoTime())

        return Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
    }

    private val centerX = 14
    private val centerY = HEIGHT / 2

    private fun close(expected: Int, actual: Int, message: String) {
        for (shift in listOf(16, 8, 0)) {
            val difference = kotlin.math.abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))

            org.junit.jupiter.api.Assertions.assertTrue(
                difference <= 3,
                "$message: expected #${expected.toUInt().toString(16)}, was #${actual.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun `HEAD's node has a ring in the text colour around it, in both themes`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val pixels = graph(theme, isHead = true)

            // The ring's stroke is between 5 and 7 dp from the center: 6 is the middle of it, left of the node
            close(scheme.onBackground.toArgb(), pixels.getColor(centerX - 6, centerY), "$theme ring")
            close(scheme.onBackground.toArgb(), pixels.getColor(centerX, centerY - 6), "$theme ring, above")
        }
    }

    @Test
    fun `the node keeps its own colour inside the ring`() {
        for (theme in listOf(Theme.Dark, Theme.Light)) {
            close(NODE_COLOR.toArgb(), graph(theme, isHead = true).getColor(centerX, centerY), "$theme node")
        }
    }

    @Test
    fun `any other commit has no ring`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val pixels = graph(theme, isHead = false)

            close(scheme.background.toArgb(), pixels.getColor(centerX - 6, centerY), "$theme where the ring would be")
            close(NODE_COLOR.toArgb(), pixels.getColor(centerX, centerY), "$theme node")
        }
    }

    @Test
    fun `the ring stays inside the node's cell and leaves the space around it alone`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val pixels = graph(theme, isHead = true)

            close(scheme.background.toArgb(), pixels.getColor(centerX - 9, centerY), "$theme left of the ring")
            close(scheme.background.toArgb(), pixels.getColor(centerX + 9, centerY), "$theme right of the ring")
            close(scheme.background.toArgb(), pixels.getColor(centerX, centerY + 9), "$theme below the ring")
        }
    }

    @Test
    fun `a selected row keeps the ring visible on its fill`() {
        for ((theme, scheme) in listOf(Theme.Dark to darkTheme, Theme.Light to lightTheme)) {
            val pixels = graph(theme, isHead = true, isSelected = true)

            close(scheme.onBackground.toArgb(), pixels.getColor(centerX - 6, centerY), "$theme ring on the selection")
        }
    }

    @Test
    fun `the subject is bold on HEAD's row, and every other style is left as it was`() {
        val style = TextStyle(fontWeight = FontWeight.Normal, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)

        assertEquals(FontWeight.Bold, style.boldWhenHead(isHead = true).fontWeight)
        assertEquals(style, style.boldWhenHead(isHead = false))
        assertEquals(style.copy(fontWeight = FontWeight.Bold), style.boldWhenHead(isHead = true))
    }
}
