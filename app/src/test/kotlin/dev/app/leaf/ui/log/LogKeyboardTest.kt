// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.log.LogMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val ROW = 40
private const val VIEWPORT = 200 // 5 rows
private const val ROWS = 40

/** The keys that move the log's selection and the scroll that follows them (fork-only). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LogKeyboardTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun key(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        shift: Boolean = false,
        control: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ) = KeyEvent(key, type, isShiftPressed = shift, isCtrlPressed = control, isAltPressed = alt, isMetaPressed = meta)

    @Test
    fun `the arrows, pages, Home and End are moves`(): Unit {
        assertEquals(LogMove.UP, key(Key.DirectionUp).logMove())
        assertEquals(LogMove.DOWN, key(Key.DirectionDown).logMove())
        assertEquals(LogMove.PAGE_UP, key(Key.PageUp).logMove())
        assertEquals(LogMove.PAGE_DOWN, key(Key.PageDown).logMove())
        assertEquals(LogMove.HOME, key(Key.MoveHome).logMove())
        assertEquals(LogMove.END, key(Key.MoveEnd).logMove())
    }

    @Test
    fun `Left and Right are not moves`(): Unit {
        assertNull(key(Key.DirectionLeft).logMove())
        assertNull(key(Key.DirectionRight).logMove())
    }

    @Test
    fun `a key release is not a move, so a press moves once`(): Unit {
        assertNull(key(Key.DirectionDown, KeyEventType.KeyUp).logMove())
    }

    @Test
    fun `with a modifier the keys are other shortcuts`(): Unit {
        for (modifier in listOf("shift", "control", "alt", "meta")) {
            fun press(k: Key) = key(
                k,
                shift = modifier == "shift",
                control = modifier == "control",
                alt = modifier == "alt",
                meta = modifier == "meta",
            ).logMove()

            for (k in listOf(Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd)) {
                assertNull(press(k), "$modifier + $k")
            }
        }
    }

    @Test
    fun `other keys are not moves`(): Unit {
        assertNull(key(Key.A).logMove())
        assertNull(key(Key.Enter).logMove())
        assertNull(key(Key.Escape).logMove())
    }

    private fun list(): LazyListState {
        val state = LazyListState()

        scene = ImageComposeScene(width = 100, height = VIEWPORT, density = Density(1f)) {
            LazyColumn(state = state) {
                items(items = (0 until ROWS).toList()) { Box(Modifier.fillMaxWidth().height(ROW.dp)) }
            }
        }
        scene!!.render(System.nanoTime())

        return state
    }

    private fun LazyListState.bringIntoView(index: Int) {
        runBlocking(Dispatchers.Swing) { bringRowIntoView(index) }
        scene!!.render(System.nanoTime())
    }

    private fun LazyListState.top() = firstVisibleItemIndex * ROW + firstVisibleItemScrollOffset

    @Test
    fun `a row in view doesn't scroll the list`(): Unit {
        val state = list()

        state.bringIntoView(0)
        state.bringIntoView(4)

        assertEquals(0, state.top())
    }

    @Test
    fun `stepping down scrolls one row at a time`(): Unit {
        val state = list()

        state.bringIntoView(5)
        assertEquals(ROW, state.top())

        state.bringIntoView(6)
        assertEquals(2 * ROW, state.top())
    }

    @Test
    fun `stepping up scrolls one row at a time`(): Unit {
        val state = list()
        state.bringIntoView(20)
        val before = state.top()

        // The row at the top is the one 4 below the first visible, so the one above it is out of view by one row
        state.bringIntoView(state.firstVisibleItemIndex - 1)

        assertEquals(before - ROW, state.top())
    }

    @Test
    fun `a row partly in view is moved in by its hidden part`(): Unit {
        val state = list()
        runBlocking(Dispatchers.Swing) { state.scrollBy(15f) }
        scene!!.render(System.nanoTime())
        assertEquals(15, state.top())

        // Row 0 is cut at the top by 15, row 5 shows 15 of its 40 at the bottom
        state.bringIntoView(0)
        assertEquals(0, state.top())

        runBlocking(Dispatchers.Swing) { state.scrollBy(15f) }
        scene!!.render(System.nanoTime())
        state.bringIntoView(5)
        assertEquals(40, state.top())
    }

    @Test
    fun `a far row below ends at the bottom and one above at the top`(): Unit {
        val state = list()

        state.bringIntoView(30)
        assertEquals((30 - 5 + 1) * ROW, state.top())

        state.bringIntoView(2)
        assertEquals(2 * ROW, state.top())
    }

    @Test
    fun `Home and End reach the first and last row`(): Unit {
        val state = list()

        state.bringIntoView(ROWS - 1)
        assertEquals((ROWS - 5) * ROW, state.top())

        state.bringIntoView(0)
        assertEquals(0, state.top())
    }

    @Test
    fun `a page is what the list shows, less one`(): Unit {
        val state = list()

        // 5 rows fit, so a layout can hold a sixth that is cut: pageRows never drops below 1
        assertEquals(state.layoutInfo.visibleItemsInfo.size - 1, state.pageRows())
        assertEquals(1, LazyListState().pageRows())
    }
}
