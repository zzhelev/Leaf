// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.window.TITLE_BAR_EDGE_PADDING
import dev.app.leaf.ui.window.TITLE_BAR_FREE_STRIP
import dev.app.leaf.ui.window.TitleBar
import dev.app.leaf.ui.window.titleBarControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

/**
 * The title bar of Leaf's own frame on Linux (docs/fork/window-chrome.md, section 10): moving and maximizing from the
 * empty bar, and the window buttons in the desktop's layout, rendered offscreen. A `ComposeWindow` stands in for the
 * window, which needs a display.
 */
class LinuxWindowTitleBarTest {
    private lateinit var window: ComposeWindow
    private val mover = RecordingMover()
    private var now = 0L

    private class RecordingMover : WindowMover {
        val moved = mutableListOf<Window>()

        override fun startMoving(window: Window) {
            moved += window
        }
    }

    @BeforeEach
    fun createWindow() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A ComposeWindow needs a display")
        window = runBlocking(Dispatchers.Swing) { ComposeWindow() }
    }

    @AfterEach
    fun disposeWindow() {
        if (::window.isInitialized) runBlocking(Dispatchers.Swing) { window.dispose() }
    }

    private fun bar(layout: WindowButtonLayout = WindowButtonLayout.DEFAULT) =
        LinuxWindowTitleBar(window, layout, mover, nanoTime = { now }, doubleClickMillis = 500)

    private fun LinuxWindowTitleBar.press(x: Float) = pointerOnBar(PointerEventType.Press, Offset(x, 20f))
    private fun LinuxWindowTitleBar.moveTo(x: Float) = pointerOnBar(PointerEventType.Move, Offset(x, 20f))
    private fun LinuxWindowTitleBar.release(x: Float) = pointerOnBar(PointerEventType.Release, Offset(x, 20f))

    @Test
    fun `a drag from the empty bar moves the window, once it goes past a few pixels`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val bar = bar()

            bar.press(100f)
            bar.moveTo(103f)
            assertTrue(mover.moved.isEmpty(), "not past the threshold yet")

            bar.moveTo(110f)
            assertEquals(listOf<Window>(window), mover.moved)

            // The window manager has the pointer now; a release that still comes moves nothing more
            bar.moveTo(150f)
            bar.release(150f)
            assertEquals(1, mover.moved.size)

            // A click alone moves nothing
            now += 1_000_000_000
            bar.press(100f)
            bar.release(100f)
            assertEquals(1, mover.moved.size)
            assertFalse(window.isMaximized())
        }

    @Test
    fun `a second press soon after maximizes or restores the window, a drag first doesn't count`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val bar = bar()

            bar.press(100f)
            bar.release(100f)
            now += 300_000_000
            bar.press(100f)
            assertTrue(window.isMaximized())
            // A drag that follows the double-click's press doesn't move the window
            bar.moveTo(140f)
            bar.release(140f)
            assertTrue(mover.moved.isEmpty())

            // A third press starts a new click
            now += 300_000_000
            bar.press(100f)
            bar.release(100f)
            assertTrue(window.isMaximized())

            // Too late to be a double-click
            now += 600_000_000
            bar.press(100f)
            bar.release(100f)
            assertTrue(window.isMaximized())

            now += 100_000_000
            bar.press(100f)
            assertFalse(window.isMaximized())
            bar.release(100f)

            // A drag, then a press soon after: the drag moved the window, the press starts a new click
            now += 1_000_000_000
            bar.press(100f)
            bar.moveTo(120f)
            now += 200_000_000
            bar.press(100f)
            bar.release(100f)
            assertEquals(1, mover.moved.size)
            assertFalse(window.isMaximized())
        }

    @Test
    fun `the buttons sit at the bar's end, after the strip that moves the window, and the tabs start 12 dp in`(): Unit =
        runBlocking(Dispatchers.Swing) {
            BarScene(bar(), width = 1000).use { scene ->
                val buttons = scene.buttons()

                assertEquals(listOf("Minimize", "Maximize", "Close"), buttons.map { it.first })
                assertEquals(1000f - 6f, buttons.last().second.right, 0.5f)
                assertEquals(TITLE_BAR_EDGE_PADDING.value, scene.content!!.left, 0.5f)
                assertEquals(buttons.first().second.left - TITLE_BAR_FREE_STRIP.value, scene.content!!.right, 0.5f)
            }
        }

    @Test
    fun `buttons at the start come before the tabs`(): Unit = runBlocking(Dispatchers.Swing) {
        val layout = WindowButtonLayout(listOf(WindowButton.CLOSE), listOf(WindowButton.MAXIMIZE))

        BarScene(bar(layout), width = 1000).use { scene ->
            val (close, maximize) = scene.buttons()

            assertEquals("Close", close.first)
            assertEquals(6f, close.second.left, 0.5f)
            assertEquals("Maximize", maximize.first)
            assertEquals(close.second.right + TITLE_BAR_EDGE_PADDING.value, scene.content!!.left, 0.5f)
        }
    }

    @Test
    fun `no buttons where the desktop shows none`(): Unit = runBlocking(Dispatchers.Swing) {
        BarScene(bar(WindowButtonLayout(emptyList(), emptyList())), width = 1000).use { scene ->
            assertTrue(scene.buttons().isEmpty())
            assertEquals(1000f - TITLE_BAR_FREE_STRIP.value, scene.content!!.right, 0.5f)
        }
    }

    @Test
    fun `the buttons minimize, maximize and restore, and close as the window's own close would`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var closing = 0
            window.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) {
                    closing++
                }
            })

            BarScene(bar(), width = 1000).use { scene ->
                scene.click(scene.button("Maximize"))
                assertTrue(window.isMaximized())

                // An undisplayed window hears nothing from a window manager: send what it would say
                window.dispatchEvent(
                    WindowEvent(window, WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.MAXIMIZED_BOTH)
                )
                scene.settle()
                assertEquals(listOf("Minimize", "Restore", "Close"), scene.buttons().map { it.first })

                scene.click(scene.button("Restore"))
                assertFalse(window.isMaximized())

                scene.click(scene.button("Minimize"))
                assertEquals(Frame.ICONIFIED, window.extendedState and Frame.ICONIFIED)

                scene.click(scene.button("Close"))
                assertEquals(1, closing)

                assertTrue(mover.moved.isEmpty(), "a press on a button doesn't move the window")
            }
        }

    @Test
    fun `a drag from the empty bar moves the window, one from a tab doesn't, nor with the secondary button`(): Unit =
        runBlocking(Dispatchers.Swing) {
            BarScene(bar(), width = 1000).use { scene ->
                scene.drag(Offset(scene.tab!!.center.x, scene.tab!!.center.y))
                assertTrue(mover.moved.isEmpty())

                // Out of the tab and over the empty bar: still the tab's drag
                scene.drag(Offset(scene.tab!!.right - 5f, 20f), by = 200f)
                assertTrue(mover.moved.isEmpty())

                scene.drag(Offset(500f, 20f), button = PointerButton.Secondary)
                assertTrue(mover.moved.isEmpty())

                scene.drag(Offset(500f, 20f))
                assertEquals(listOf<Window>(window), mover.moved)

                // From the strip before the buttons, and over them
                scene.drag(Offset(scene.button("Minimize").left - 20f, 20f), by = 60f)
                assertEquals(2, mover.moved.size)
            }
        }

    @Test
    fun `renders the bar with its buttons, for a look at them`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.LEAF)) {
            for (theme in listOf(Theme.Light, Theme.Dark)) {
                BarScene(bar(), width = 900, theme = theme, style = style, scale = 2f).use { scene ->
                    scene.hover(scene.button("Close"))
                    scene.png(File(folder, "linux-title-bar-${style.id}-${theme.name.lowercase()}.png"))
                }
            }
        }
    }

    private inner class BarScene(
        bar: LinuxWindowTitleBar,
        width: Int,
        theme: Theme = Theme.Light,
        style: StyleDefinition = BuiltInStyles.DEFAULT,
        private val scale: Float = 1f,
    ) : AutoCloseable {
        var content: Rect? = null
        var tab: Rect? = null

        private val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (40 * scale).toInt(),
            density = Density(scale),
            coroutineContext = Dispatchers.Swing,
        ) {
            AppTheme(theme, style = style) {
                TitleBar(bar, window, isFullscreen = false) { controls ->
                    Row(Modifier.fillMaxWidth().onGloballyPositioned { content = it.boundsInRoot().scaled() }) {
                        Box(
                            Modifier.width(160.dp).height(28.dp).titleBarControl(controls)
                                .onGloballyPositioned { tab = it.boundsInRoot().scaled() }
                        )
                    }
                }
            }
        }

        init {
            runBlocking { settle() }
        }

        private fun Rect.scaled() = Rect(left / scale, top / scale, right / scale, bottom / scale)

        suspend fun settle() = repeat(4) {
            scene.render(System.nanoTime())
            delay(20)
        }

        fun buttons(): List<Pair<String, Rect>> = scene.semanticsOwners
            .flatMap { it.getAllSemanticsNodes(false) }
            .mapNotNull { node -> node.description()?.let { it to node.boundsInRoot.scaled() } }
            .filter { it.first in setOf("Minimize", "Maximize", "Restore", "Close") }
            .sortedBy { it.second.left }

        fun button(name: String): Rect = buttons().first { it.first == name }.second

        suspend fun click(target: Rect) {
            val position = Offset(target.center.x * scale, target.center.y * scale)

            scene.sendPointerEvent(PointerEventType.Move, position)
            scene.sendPointerEvent(
                PointerEventType.Press, position,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
            )
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
            settle()
        }

        /** A press at [at], a move [by] to the right with the button down, and the release. */
        suspend fun drag(at: Offset, by: Float = 30f, button: PointerButton = PointerButton.Primary) {
            val position = Offset(at.x * scale, at.y * scale)
            val end = Offset((at.x + by) * scale, at.y * scale)
            val buttons = if (button == PointerButton.Primary) {
                PointerButtons(isPrimaryPressed = true)
            } else {
                PointerButtons(isSecondaryPressed = true)
            }

            scene.sendPointerEvent(PointerEventType.Move, position)
            scene.sendPointerEvent(PointerEventType.Press, position, buttons = buttons, button = button)
            scene.sendPointerEvent(PointerEventType.Move, end, buttons = buttons)
            scene.sendPointerEvent(PointerEventType.Release, end, button = button)
            // Far apart in time, so that two presses are never a double-click
            now += 10_000_000_000
            settle()
        }

        suspend fun hover(target: Rect) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(target.center.x * scale, target.center.y * scale))
            settle()
        }

        fun png(file: File) {
            file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }

        override fun close() = scene.close()

        private fun SemanticsNode.description(): String? =
            config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    }
}

/** Leaf's own frame on Linux: a line around the window unless it's maximized. */
class LinuxWindowFrameTest {
    @Test
    fun `the frame draws a line around the content, none when maximized, and nothing without a frame`(): Unit =
        runBlocking(Dispatchers.Swing) {
            assumeFalse(GraphicsEnvironment.isHeadless(), "A ComposeWindow needs a display")
            val window = ComposeWindow()

            try {
                fun contentIn(frame: LinuxFrame?): Rect {
                    var content: Rect? = null

                    ImageComposeScene(width = 300, height = 200, density = Density(1f), coroutineContext = Dispatchers.Swing) {
                        AppTheme(Theme.Light) {
                            LinuxWindowFrame(frame, window) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned { content = it.boundsInRoot() })
                            }
                        }
                    }.use { it.render() }

                    return content!!
                }

                assertEquals(Rect(1f, 1f, 299f, 199f), contentIn(LinuxFrame(WindowButtonLayout.DEFAULT)))
                assertEquals(Rect(0f, 0f, 300f, 200f), contentIn(null))

                window.extendedState = Frame.MAXIMIZED_BOTH
                assertEquals(Rect(0f, 0f, 300f, 200f), contentIn(LinuxFrame(WindowButtonLayout.DEFAULT)))
            } finally {
                window.dispose()
            }
        }
}
