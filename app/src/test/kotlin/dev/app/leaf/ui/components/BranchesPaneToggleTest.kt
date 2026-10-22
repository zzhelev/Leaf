// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.theme.AppTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** The bottom bar's button for the branches pane (fork-only): its name, its icon for each state, and its click. */
@OptIn(ExperimentalComposeUiApi::class)
class BranchesPaneToggleTest {
    private var scene: ImageComposeScene? = null
    private var time = 0L

    private var isFolded by mutableStateOf(false)
    private var clicks = 0

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun render() {
        scene = ImageComposeScene(width = 120, height = 60, density = Density(2f)) {
            AppTheme {
                BranchesPaneToggle(isFolded = isFolded, onToggle = { clicks++ })
            }
        }
        frames()
    }

    private fun frames(count: Int = 3) = repeat(count) {
        time += 50
        scene!!.render(time * 1_000_000)
    }

    private fun descriptions() = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(false) }
        .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    /** The clickable box, which holds the icon. */
    private fun button(): SemanticsNode = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(false) }
        .first { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun png() = scene!!.render(time * 1_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes.toList()

    @Test
    fun `it offers to hide the pane while it shows, and to show it while it is folded`() {
        render()
        assertEquals(listOf("Hide the branches pane"), descriptions())

        isFolded = true
        frames()

        assertEquals(listOf("Show the branches pane"), descriptions())
    }

    @Test
    fun `its icon differs between the states`() {
        render()
        val shown = png()

        isFolded = true
        frames()

        assertNotEquals(shown, png())
    }

    @Test
    fun `a click toggles once, and the button is a 26 dp square`() {
        render()
        val node = button()
        val at: Offset = node.boundsInRoot.center

        assertEquals(26f * 2, node.boundsInRoot.width, 1f)
        assertEquals(26f * 2, node.boundsInRoot.height, 1f)

        time += 100
        scene!!.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
        scene!!.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        time += 20
        scene!!.sendPointerEvent(PointerEventType.Release, at, timeMillis = time, button = PointerButton.Primary)
        frames()

        assertEquals(1, clicks)
    }

    @Test
    fun `a click outside the button does nothing`() {
        render()

        time += 100
        scene!!.sendPointerEvent(PointerEventType.Move, Offset(110f, 50f), timeMillis = time)
        scene!!.sendPointerEvent(PointerEventType.Press, Offset(110f, 50f), timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        time += 20
        scene!!.sendPointerEvent(PointerEventType.Release, Offset(110f, 50f), timeMillis = time, button = PointerButton.Primary)
        frames()

        assertEquals(0, clicks)
    }
}
