// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TitleBarPointerTest {
    private val tabs = Rect(left = 80f, top = 0f, right = 480f, bottom = 40f)
    private val onTab = Offset(200f, 20f)
    private val emptyBar = Offset(800f, 20f)

    private fun TitleBarPointer.takes(type: PointerEventType, position: Offset) = leafTakes(type, position, listOf(tabs))

    @Test
    fun `a press on a control is Leaf's and a press on the empty bar is the window's`() {
        val pointer = TitleBarPointer()

        assertEquals(true, pointer.takes(PointerEventType.Press, onTab))
        assertEquals(true, pointer.takes(PointerEventType.Release, onTab))
        assertEquals(false, pointer.takes(PointerEventType.Press, emptyBar))
        assertEquals(false, pointer.takes(PointerEventType.Release, emptyBar))
    }

    @Test
    fun `dragging a tab out of the tabs stays Leaf's until the release`() {
        val pointer = TitleBarPointer()

        pointer.takes(PointerEventType.Press, onTab)

        assertEquals(true, pointer.takes(PointerEventType.Move, emptyBar))
        assertEquals(true, pointer.takes(PointerEventType.Release, emptyBar))
        assertEquals(false, pointer.takes(PointerEventType.Move, emptyBar))
    }

    @Test
    fun `a drag from the empty bar over the tabs stays the window's`() {
        val pointer = TitleBarPointer()

        pointer.takes(PointerEventType.Press, emptyBar)

        assertEquals(true, pointer.takes(PointerEventType.Move, onTab), "hovering a tab shows its hover")
        assertEquals(true, pointer.takes(PointerEventType.Release, onTab))
        assertEquals(false, pointer.takes(PointerEventType.Press, emptyBar))
    }

    @Test
    fun `without controls the whole bar is the window's`() {
        val pointer = TitleBarPointer()

        assertEquals(false, pointer.leafTakes(PointerEventType.Press, onTab, emptyList()))
        assertEquals(false, pointer.leafTakes(PointerEventType.Move, onTab, emptyList()))
    }
}
