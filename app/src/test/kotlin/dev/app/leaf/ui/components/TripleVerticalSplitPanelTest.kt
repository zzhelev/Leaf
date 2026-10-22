// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three panes of the repository view. A width of 0 leaves a side pane and its handle out (fork-only): the branches
 * pane's fold has always done it for the first, and the changes pane's does it for the third.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TripleVerticalSplitPanelTest {
    private var scene: ImageComposeScene? = null
    private var time = 0L

    private var firstWidth by mutableStateOf(220f)
    private var thirdWidth by mutableStateOf(330f)

    private var firstComposed = false
    private var thirdComposed = false
    private var secondWidth = 0
    private var thirdDragsStarted = 0
    private var firstDragsStarted = 0

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun render() {
        scene = ImageComposeScene(width = 1000, height = 400, density = Density(1f)) {
            TripleVerticalSplitPanel(
                modifier = Modifier.fillMaxSize(),
                firstWidth = firstWidth,
                thirdWidth = thirdWidth,
                first = {
                    SideEffect { firstComposed = true }
                    Box(Modifier.fillMaxSize())
                },
                second = {
                    Box(Modifier.fillMaxSize().onSizeChanged { secondWidth = it.width })
                },
                third = {
                    SideEffect { thirdComposed = true }
                    Box(Modifier.fillMaxSize())
                },
                onFirstSizeDragStarted = { firstDragsStarted++ },
                onFirstSizeChange = {},
                onFirstSizeDragStopped = {},
                onThirdSizeDragStarted = { thirdDragsStarted++ },
                onThirdSizeChange = {},
                onThirdSizeDragStopped = {},
            )
        }
        frames()
    }

    private fun frames(count: Int = 3) = repeat(count) {
        time += 50
        scene!!.render(time * 1_000_000)
    }

    private fun drag(from: Offset) {
        time += 100
        scene!!.sendPointerEvent(PointerEventType.Move, from, timeMillis = time)
        scene!!.sendPointerEvent(PointerEventType.Press, from, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)

        for (step in 1..6) {
            time += 20
            scene!!.sendPointerEvent(
                PointerEventType.Move,
                from + Offset(step * 10f, 0f),
                timeMillis = time,
                buttons = PointerButtons(isPrimaryPressed = true),
            )
        }

        time += 20
        scene!!.sendPointerEvent(PointerEventType.Release, from + Offset(60f, 0f), timeMillis = time, button = PointerButton.Primary)
        frames()
    }

    @Test
    fun `with all three widths the middle takes what the sides and their handles leave`() {
        render()

        assertTrue(firstComposed)
        assertTrue(thirdComposed)
        assertEquals(1000 - 220 - 8 - 8 - 330, secondWidth)
    }

    @Test
    fun `a third width of 0 leaves the third pane out, and its 8 dp handle`() {
        thirdWidth = 0f
        render()

        assertTrue(firstComposed)
        assertFalse(thirdComposed)
        assertEquals(1000 - 220 - 8, secondWidth)
    }

    @Test
    fun `a first width of 0 leaves the first pane out, and its handle`() {
        firstWidth = 0f
        render()

        assertFalse(firstComposed)
        assertTrue(thirdComposed)
        assertEquals(1000 - 8 - 330, secondWidth)
    }

    @Test
    fun `both folded, the middle has the whole width`() {
        firstWidth = 0f
        thirdWidth = 0f
        render()

        assertFalse(firstComposed)
        assertFalse(thirdComposed)
        assertEquals(1000, secondWidth)
    }

    @Test
    fun `the third pane comes back when its width does, with the middle giving it room`() {
        thirdWidth = 0f
        render()
        assertFalse(thirdComposed)

        thirdWidth = 330f
        frames()

        assertTrue(thirdComposed)
        assertEquals(1000 - 220 - 8 - 8 - 330, secondWidth)
    }

    @Test
    fun `the third handle takes a drag while the pane shows, and there is none to drag while it is out`() {
        render()
        val handle = Offset(1000f - 330f - 4f, 200f)

        drag(handle)
        assertEquals(1, thirdDragsStarted)

        thirdWidth = 0f
        frames()
        thirdDragsStarted = 0

        // Where the handle was the middle pane is now, which takes no drag
        drag(Offset(1000f - 4f, 200f))
        drag(handle)

        assertEquals(0, thirdDragsStarted)
    }

    @Test
    fun `the first handle still takes a drag while the third pane is out`() {
        thirdWidth = 0f
        render()

        drag(Offset(220f + 4f, 200f))

        assertEquals(1, firstDragsStarted)
        assertEquals(0, thirdDragsStarted)
    }
}
