// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A view drawn over another one, as the diff pane is over the log, rendered offscreen at 1 px per dp. */
@OptIn(ExperimentalComposeUiApi::class)
class CoveringWhatIsBelowTest {
    private var clicksBelow = 0
    private var hoversBelow = 0
    private var buttonClicks = 0
    private var scene: ImageComposeScene? = null
    private var time = 0L

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `without it, a click on an empty part of the top view reaches the view below`() {
        render(covering = false)

        click(EMPTY_SPOT)

        assertEquals(1, clicksBelow)
        assertEquals(1, hoversBelow)
    }

    @Test
    fun `with it, the view below gets neither clicks nor hover`() {
        render(covering = true)

        click(EMPTY_SPOT)

        assertEquals(0, clicksBelow)
        assertEquals(0, hoversBelow)
    }

    @Test
    fun `with it, what is inside the top view still gets its clicks`() {
        render(covering = true)

        click(BUTTON)

        assertEquals(1, buttonClicks)
        assertEquals(0, clicksBelow)
    }

    private fun render(covering: Boolean) {
        scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Enter) { hoversBelow++ }
                        .clickable { clicksBelow++ }
                )
                Box(
                    modifier = Modifier
                        .then(if (covering) Modifier.coveringWhatIsBelow() else Modifier)
                        .fillMaxSize()
                        .background(Color.White)
                ) {
                    Box(modifier = Modifier.size(50.dp).clickable { buttonClicks++ })
                }
            }
        }
        scene!!.render()
    }

    private fun click(position: Offset) {
        val scene = scene!!

        time += 100
        scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = time)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            timeMillis = time,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        time += 20
        scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = time, button = PointerButton.Primary)
        scene.render()
    }

    private companion object {
        val EMPTY_SPOT = Offset(150f, 150f)
        val BUTTON = Offset(25f, 25f)
    }
}
