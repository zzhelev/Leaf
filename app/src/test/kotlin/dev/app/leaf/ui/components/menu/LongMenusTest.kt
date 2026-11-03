// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.showPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val WINDOW_WIDTH = 400
private const val WINDOW_HEIGHT = 300

/** The menu's top and bottom: 8 px from the window's edges (fork-only, MENU_WINDOW_MARGIN at a density of 1). */
private const val TOP = 8f
private const val BOTTOM = WINDOW_HEIGHT - 8f

/** An entry is clear of the fades (16 px) unless the menu is scrolled to that end. */
private const val FADE = 16f

/** How much wider a menu is while it scrolls than its entries, which end before the scrollbar (SCROLLBAR_ROOM). */
private const val SCROLLBAR_ROOM = 6f

/** Menus taller than the window fit it, scroll and say so, and the keys reach every entry (fork-only). */
@OptIn(ExperimentalComposeUiApi::class)
class LongMenusTest {
    private var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var scene: ImageComposeScene? = null
    private val picked = mutableListOf<String>()
    private var dismissed = false

    @AfterEach
    fun close() {
        scene?.close()
    }

    @Test
    fun `a right-click menu taller than the window is cut to it, a margin from its edges, and every entry keeps its height`(): Unit =
        runBlocking(Dispatchers.Swing) {
            openContextMenu(x = 100, y = 150, count = 30)

            assertEquals(TOP, entry("Entry 1").positionInWindow.y)
            assertEquals(100f, entry("Entry 1").positionInWindow.x)
            assertEquals(BOTTOM, visibleBottom(30))

            for (i in 1..30) {
                assertEquals(32, entry("Entry $i").size.height, "Entry $i")
            }
        }

    @Test
    fun `the wheel scrolls a long right-click menu to its last entry`(): Unit = runBlocking(Dispatchers.Swing) {
        openContextMenu(x = 100, y = 150, count = 30)

        scene!!.sendPointerEvent(PointerEventType.Move, Offset(150f, 150f))
        val before = entry("Entry 1").positionInWindow.y

        repeat(10) {
            scene!!.sendPointerEvent(PointerEventType.Scroll, Offset(150f, 150f), scrollDelta = Offset(0f, 3f))
            settle()
        }

        assertTrue(entry("Entry 1").positionInWindow.y < before, "the wheel scrolled: ${entry("Entry 1").positionInWindow.y}")

        repeat(30) {
            scene!!.sendPointerEvent(PointerEventType.Scroll, Offset(150f, 150f), scrollDelta = Offset(0f, 3f))
        }
        settle()

        val last = entry("Entry 30").boundsInWindow
        assertEquals(32f, last.height)
        assertEquals(BOTTOM, last.bottom)
    }

    @Test
    fun `the arrow keys reach every entry, and keep it in view clear of the fades`(): Unit =
        runBlocking(Dispatchers.Swing) {
            openContextMenu(x = 100, y = 150, count = 30)

            for (i in 1..30) {
                press(Key.DirectionDown)

                val focused = focusedEntry()
                assertEquals("Entry $i", label(focused))

                val bounds = focused.boundsInWindow
                assertEquals(32f, bounds.height, "Entry $i is whole")

                when (i) {
                    1 -> assertEquals(TOP, bounds.top)
                    30 -> assertEquals(BOTTOM, bounds.bottom)
                    else -> {
                        assertTrue(bounds.top >= TOP + FADE - 0.5f, "Entry $i: ${bounds.top}")
                        assertTrue(bounds.bottom <= BOTTOM - FADE + 0.5f, "Entry $i: ${bounds.bottom}")
                    }
                }
            }

            for (i in 29 downTo 1) {
                press(Key.DirectionUp)
                assertEquals("Entry $i", label(focusedEntry()))
                assertEquals(32f, focusedEntry().boundsInWindow.height, "Entry $i is whole")
            }
        }

    @Test
    fun `End and Home go to the last and the first entry that can be picked`(): Unit = runBlocking(Dispatchers.Swing) {
        val entries = entries(30).toMutableList()
        entries[0] = ContextMenuElement.ContextTextEntry("Entry 1", isEnabled = false)
        entries[29] = ContextMenuElement.ContextTextEntry("Entry 30", isEnabled = false)
        openContextMenu(x = 100, y = 150, elements = entries)

        press(Key.MoveEnd)
        assertEquals("Entry 29", label(focusedEntry()))
        assertEquals(32f, focusedEntry().boundsInWindow.height)

        press(Key.MoveHome)
        assertEquals("Entry 2", label(focusedEntry()))
        assertEquals(32f, focusedEntry().boundsInWindow.height)
    }

    @Test
    fun `the entry that the keys moved to is highlighted`(): Unit = runBlocking(Dispatchers.Swing) {
        openContextMenu(x = 100, y = 20, count = 5)
        press(Key.DirectionDown)
        press(Key.DirectionDown)

        val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
        fun fill(label: String) = entry(label).boundsInWindow.let { bitmap.getColor(it.right.toInt() - 20, it.center.y.toInt()) }

        assertEquals("Entry 2", label(focusedEntry()))
        assertTrue(fill("Entry 2") != fill("Entry 1"), "the focused entry has a fill of its own")
        assertEquals(fill("Entry 1"), fill("Entry 3"))
    }

    @Test
    fun `Enter picks the entry that the keys moved to, and Esc closes the menu`(): Unit = runBlocking(Dispatchers.Swing) {
        openContextMenu(x = 100, y = 150, count = 30)

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf("Entry 2"), picked)
        assertTrue(dismissed)

        dismissed = false
        openContextMenu(x = 100, y = 150, count = 30)
        press(Key.Escape)

        assertTrue(dismissed)
        assertEquals(listOf("Entry 2"), picked)
    }

    @Test
    fun `a menu that fits opens at the pointer`(): Unit = runBlocking(Dispatchers.Swing) {
        openContextMenu(x = 50, y = 60, count = 5)

        assertEquals(Offset(50f, 60f), entry("Entry 1").positionInWindow)
    }

    @Test
    fun `a menu that fits but not at the pointer moves just enough to keep a margin from the window's edges`(): Unit =
        runBlocking(Dispatchers.Swing) {
            openContextMenu(x = 395, y = 295, count = 5)

            assertEquals(BOTTOM, entry("Entry 5").boundsInWindow.bottom)
            assertEquals(WINDOW_WIDTH - 8f, entry("Entry 5").boundsInWindow.right)
        }

    @Test
    fun `a menu that scrolls shows its scrollbar, and one that fits shows none`(): Unit = runBlocking(Dispatchers.Swing) {
        openContextMenu(x = 100, y = 150, count = 30)
        assertTrue(thumbPixels(count = 30, room = SCROLLBAR_ROOM) > 40, "a thumb of about 84 px")

        content = null
        settle()
        openContextMenu(x = 100, y = 20, count = 5)
        assertEquals(0, thumbPixels(count = 5, room = 0f))
    }

    @Test
    fun `dragging the scrollbar scrolls the menu without picking an entry`(): Unit = runBlocking(Dispatchers.Swing) {
        openContextMenu(x = 100, y = 150, count = 30)
        val right = entry("Entry 1").boundsInWindow.right + SCROLLBAR_ROOM
        val start = Offset(right - 5f, TOP + 20f)

        scene!!.sendPointerEvent(PointerEventType.Move, start)
        scene!!.sendPointerEvent(
            PointerEventType.Press,
            start,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        settle()

        for (step in 1..10) {
            scene!!.sendPointerEvent(
                PointerEventType.Move,
                start + Offset(0f, step * 10f),
                buttons = PointerButtons(isPrimaryPressed = true),
            )
        }

        scene!!.sendPointerEvent(
            PointerEventType.Release,
            start + Offset(0f, 100f),
            buttons = PointerButtons(),
            button = PointerButton.Primary,
        )
        settle()

        assertTrue(entry("Entry 1").positionInWindow.y < TOP - 100f, "${entry("Entry 1").positionInWindow.y}")
        assertEquals(emptyList<String>(), picked)
        assertFalse(dismissed)
    }

    @Test
    fun `a real right-click opens the menu at the pointer, cut to the window when it's taller`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val elements = entries(30)
            content = {
                ContextMenu(items = { elements }) {
                    Box(Modifier.fillMaxSize())
                }
            }
            startScene()

            val pointer = Offset(120f, 280f)
            scene!!.sendPointerEvent(PointerEventType.Move, pointer)
            scene!!.sendPointerEvent(
                PointerEventType.Press,
                pointer,
                buttons = PointerButtons(isSecondaryPressed = true),
                button = PointerButton.Secondary,
            )
            scene!!.sendPointerEvent(PointerEventType.Release, pointer, buttons = PointerButtons(), button = PointerButton.Secondary)
            settle()

            assertEquals(Offset(120f, TOP), entry("Entry 1").positionInWindow)
            assertEquals(BOTTOM, visibleBottom(30))
        }

    @Test
    fun `a header menu taller than the window scrolls instead of dropping its rows, and End reaches its last option`(): Unit =
        runBlocking(Dispatchers.Swing) {
            content = {
                Box(Modifier.offset(x = 300.dp, y = 20.dp).size(20.dp)) {
                    SortMenuPopup(
                        items = List(30) { i -> SortMenuItem.Option("Option ${i + 1}", isChecked = false, onClick = {}) },
                        onDismissRequest = { dismissed = true },
                    )
                }
            }
            startScene()

            val height = entry("Option 1").size.height
            assertTrue(height > 20, "an option's height: $height")

            for (i in 1..30) {
                assertEquals(height, entry("Option $i").size.height, "Option $i")
            }

            assertTrue(entry("Option 1").positionInWindow.y >= TOP)
            assertTrue(visibleBottom(30, "Option") <= BOTTOM)

            press(Key.MoveEnd)
            assertEquals("Option 30", label(focusedEntry()))
            assertEquals(height.toFloat(), focusedEntry().boundsInWindow.height)
        }

    private fun entries(count: Int) = List(count) { i ->
        val label = "Entry ${i + 1}"
        ContextMenuElement.ContextTextEntry(label, onClick = { picked += label })
    }

    private suspend fun openContextMenu(x: Int, y: Int, count: Int) = openContextMenu(x, y, entries(count))

    private suspend fun openContextMenu(x: Int, y: Int, elements: List<ContextMenuElement>) {
        content = {
            showPopup(
                showIcons = true,
                x = x,
                y = y,
                contextMenuElements = elements,
                onDismissRequest = {
                    dismissed = true
                    content = null
                },
            )
        }

        if (scene == null) {
            startScene()
        } else {
            settle()
        }
    }

    private suspend fun startScene() {
        scene = ImageComposeScene(
            width = WINDOW_WIDTH,
            height = WINDOW_HEIGHT,
            density = Density(1f),
            coroutineContext = Dispatchers.Swing,
        ) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.fillMaxSize()) {
                    content?.invoke()
                }
            }
        }
        settle()
    }

    /** Lets the focus move and the scroll that it starts end: renders frames with the clock running. */
    private suspend fun settle() {
        repeat(15) {
            scene!!.render(System.nanoTime())
            delay(16)
        }
    }

    @OptIn(InternalComposeUiApi::class)
    private suspend fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
        settle()
    }

    private fun rows(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }

        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

        return nodes.filter { it.config.contains(SemanticsActions.OnClick) && label(it) != null }
    }

    private fun entry(label: String): SemanticsNode = rows().first { label(it) == label }

    private fun focusedEntry(): SemanticsNode = rows().single { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun label(node: SemanticsNode): String? =
        node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    /** Where the menu's entries end on screen: the bottom of the lowest one in view. */
    private fun visibleBottom(count: Int, prefix: String = "Entry"): Float =
        (1..count).map { entry("$prefix $it").boundsInWindow }.filter { it.height > 0f }.maxOf { it.bottom }

    /**
     * How many pixels of the column where the thumb is (3 px in from the menu's right edge, 4 px wide) differ from the
     * column 20 px further in, which is the entries' empty end, from the menu's first entry in view to its last. The
     * menu is [room] wider than its entries.
     */
    private fun thumbPixels(count: Int, room: Float): Int {
        val first = entry("Entry 1").boundsInWindow
        val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
        val thumbX = (first.right + room - 5f).toInt()
        val insideX = (first.right + room - 25f).toInt()

        return (first.top.toInt() until visibleBottom(count).toInt()).count { y ->
            bitmap.getColor(thumbX, y) != bitmap.getColor(insideX, y)
        }
    }
}
