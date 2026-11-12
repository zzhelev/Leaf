// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.window.SavedWindow
import dev.app.leaf.domain.window.ShownPlacement
import dev.app.leaf.domain.window.ShownWindow
import dev.app.leaf.domain.window.WindowMode
import dev.app.leaf.domain.window.WindowOpening
import dev.app.leaf.domain.window.WindowRect
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

private val SCREEN = WindowRect(x = 0, y = 25, width = 1512, height = 957)
private val ON_SCREEN = WindowRect(x = 200, y = 120, width = 1000, height = 700)

/** Where Leaf's window opens, and keeping where it is (fork-only). */
class WindowPlacementsTest {
    private var stored: SavedWindow? = null
    private val repository = mockk<AppSettingsRepository>(relaxed = true) {
        every { savedWindow } answers { stored }
        every { savedWindow = any() } answers { stored = firstArg() }
    }

    @Test
    fun `the first run opens maximized, at the default size and the system's place under it`() {
        val state = windowStateOf(WindowPlacements(repository).opening(listOf(SCREEN)))

        assertEquals(WindowPlacement.Maximized, state.placement)
        assertEquals(WindowPosition.PlatformDefault, state.position)
        assertEquals(DEFAULT_WINDOW_SIZE, state.size)
        assertEquals(DpSize(1280.dp, 720.dp), DEFAULT_WINDOW_SIZE)
    }

    @Test
    fun `a floating window opens where it was`() {
        stored = SavedWindow(WindowMode.FLOATING, ON_SCREEN)

        val state = windowStateOf(WindowPlacements(repository).opening(listOf(SCREEN)))

        assertEquals(WindowPlacement.Floating, state.placement)
        assertEquals(WindowPosition(200.dp, 120.dp), state.position)
        assertEquals(DpSize(1000.dp, 700.dp), state.size)
    }

    @Test
    fun `a maximized window opens maximized, over where it floated`() {
        stored = SavedWindow(WindowMode.MAXIMIZED, ON_SCREEN)

        val state = windowStateOf(WindowPlacements(repository).opening(listOf(SCREEN)))

        assertEquals(WindowPlacement.Maximized, state.placement)
        assertEquals(WindowPosition(200.dp, 120.dp), state.position)
        assertEquals(DpSize(1000.dp, 700.dp), state.size)
    }

    @Test
    fun `a window that never floated opens at the default size and place, as it was`() {
        assertEquals(
            WindowOpening(WindowMode.FLOATING, floating = null).let(::windowStateOf).run { Triple(placement, position, size) },
            Triple(WindowPlacement.Floating, WindowPosition.PlatformDefault, DEFAULT_WINDOW_SIZE),
        )
    }

    @Test
    fun `a window on a monitor that is gone opens on the main screen`() {
        stored = SavedWindow(WindowMode.FLOATING, WindowRect(x = -2000, y = 100, width = 1000, height = 700))

        assertEquals(
            WindowOpening(WindowMode.FLOATING, WindowRect(x = 256, y = 25 + 128, width = 1000, height = 700)),
            WindowPlacements(repository).opening(listOf(SCREEN)),
        )
    }

    @Test
    fun `keeping writes only what changed, and reads the preferences once`() {
        val placements = WindowPlacements(repository)
        val floating = ShownWindow(ShownPlacement.FLOATING, isMinimized = false, bounds = ON_SCREEN)

        placements.keep(floating)
        placements.keep(floating)
        assertEquals(SavedWindow(WindowMode.FLOATING, ON_SCREEN), stored)

        placements.keep(floating.copy(placement = ShownPlacement.FULL_SCREEN, bounds = WindowRect(0, 0, 1512, 982)))
        placements.keep(floating.copy(isMinimized = true, bounds = WindowRect(-32_000, -32_000, 160, 28)))
        assertEquals(SavedWindow(WindowMode.FLOATING, ON_SCREEN), stored)

        placements.keep(floating.copy(placement = ShownPlacement.MAXIMIZED, bounds = SCREEN))
        assertEquals(SavedWindow(WindowMode.MAXIMIZED, ON_SCREEN), stored)

        verify(exactly = 2) { repository.savedWindow = any() }
        verify(exactly = 1) { repository.savedWindow }
    }

    @Test
    fun `what the window shows, as the keeping reads it`() {
        val bounds = Rectangle(-1200, 40, 1100, 700)

        assertEquals(
            ShownWindow(ShownPlacement.FLOATING, isMinimized = false, bounds = WindowRect(-1200, 40, 1100, 700)),
            shownWindow(WindowPlacement.Floating, isMinimized = false, bounds = bounds),
        )
        assertEquals(ShownPlacement.MAXIMIZED, shownWindow(WindowPlacement.Maximized, false, bounds).placement)
        assertEquals(ShownPlacement.FULL_SCREEN, shownWindow(WindowPlacement.Fullscreen, false, bounds).placement)
        assertTrue(shownWindow(WindowPlacement.Floating, isMinimized = true, bounds = bounds).isMinimized)
    }

    @Test
    fun `the screens' usable areas start with the main screen, and lie within their screens`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Screens need a display")
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val main = environment.defaultScreenDevice.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(main)

        val areas = usableScreenAreas()

        assertEquals(environment.screenDevices.size, areas.size)
        assertEquals(
            WindowRect(
                main.bounds.x + insets.left,
                main.bounds.y + insets.top,
                main.bounds.width - insets.left - insets.right,
                main.bounds.height - insets.top - insets.bottom,
            ),
            areas.first(),
        )
        areas.forEach { area ->
            assertTrue(
                environment.screenDevices.any { it.defaultConfiguration.bounds.contains(area.toRectangle()) },
                "$area",
            )
        }
    }

    @Test
    fun `the window is kept once it stays as it is for a moment, and again as it closes`(): Unit =
        runBlocking(Dispatchers.Swing) {
            assumeFalse(GraphicsEnvironment.isHeadless(), "A frame needs a display")
            val window = TestFrame()
            val placements = WindowPlacements(repository)
            val state = windowStateOf(WindowOpening(WindowMode.FLOATING, ON_SCREEN))
            var isOpen by mutableStateOf(true)

            try {
                window.bounds = ON_SCREEN.toRectangle()

                ImageComposeScene(width = 100, height = 100, coroutineContext = Dispatchers.Swing) {
                    if (isOpen) KeepWindowPlacement(window, state, placements)
                }.use { scene ->
                    suspend fun waitFor(milliseconds: Long) {
                        val end = System.nanoTime() + milliseconds * 1_000_000

                        while (System.nanoTime() < end) {
                            scene.render(System.nanoTime())
                            delay(20)
                        }
                    }

                    // As it opens
                    waitFor(KEEP_WINDOW_DELAY_MS + 300)
                    assertEquals(SavedWindow(WindowMode.FLOATING, ON_SCREEN), stored)

                    // Dragged: each move starts the wait again, so only where it ends up is kept
                    repeat(5) { step ->
                        val moved = ON_SCREEN.copy(x = ON_SCREEN.x + (step + 1) * 40)
                        window.bounds = moved.toRectangle()
                        state.position = WindowPosition(moved.x.dp, moved.y.dp)
                        waitFor(KEEP_WINDOW_DELAY_MS / 4)
                    }
                    assertEquals(SavedWindow(WindowMode.FLOATING, ON_SCREEN), stored)

                    waitFor(KEEP_WINDOW_DELAY_MS + 300)
                    assertEquals(SavedWindow(WindowMode.FLOATING, ON_SCREEN.copy(x = 400)), stored)
                    verify(exactly = 2) { repository.savedWindow = any() }

                    // Resized and closed at once, before the wait is over
                    val resized = ON_SCREEN.copy(x = 400, width = 1100)
                    window.bounds = resized.toRectangle()
                    state.size = DpSize(1100.dp, 700.dp)
                    waitFor(40)
                    isOpen = false
                    waitFor(60)

                    assertEquals(SavedWindow(WindowMode.FLOATING, resized), stored)
                    verify(exactly = 3) { repository.savedWindow = any() }
                }
            } finally {
                window.dispose()
            }
        }

    @Test
    fun `a window that goes full screen keeps what it was before`(): Unit = runBlocking(Dispatchers.Swing) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A frame needs a display")
        stored = SavedWindow(WindowMode.MAXIMIZED, ON_SCREEN)
        val window = TestFrame()
        val placements = WindowPlacements(repository)
        val state = windowStateOf(WindowOpening(WindowMode.MAXIMIZED, ON_SCREEN))
        var isOpen by mutableStateOf(true)

        try {
            window.bounds = Rectangle(0, 0, 1512, 982)

            ImageComposeScene(width = 100, height = 100, coroutineContext = Dispatchers.Swing) {
                if (isOpen) KeepWindowPlacement(window, state, placements)
            }.use { scene ->
                suspend fun waitFor(milliseconds: Long) {
                    val end = System.nanoTime() + milliseconds * 1_000_000

                    while (System.nanoTime() < end) {
                        scene.render(System.nanoTime())
                        delay(20)
                    }
                }

                state.placement = WindowPlacement.Fullscreen
                waitFor(KEEP_WINDOW_DELAY_MS + 300)
                isOpen = false
                waitFor(60)

                assertEquals(SavedWindow(WindowMode.MAXIMIZED, ON_SCREEN), stored)
                verify(exactly = 0) { repository.savedWindow = any() }
            }
        } finally {
            window.dispose()
        }
    }
    @Test
    fun `nothing is kept before the window shows, not even as Leaf quits then`(): Unit = runBlocking(Dispatchers.Swing) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A frame needs a display")
        stored = SavedWindow(WindowMode.FLOATING, ON_SCREEN)
        val window = TestFrame(isShown = false)
        val placements = WindowPlacements(repository)
        val state = windowStateOf(WindowOpening(WindowMode.FLOATING, ON_SCREEN))
        var isOpen by mutableStateOf(true)

        try {
            // AWT's first bounds, before Compose gives the window its own
            window.bounds = Rectangle(0, 3, 80, 28)

            ImageComposeScene(width = 100, height = 100, coroutineContext = Dispatchers.Swing) {
                if (isOpen) KeepWindowPlacement(window, state, placements)
            }.use { scene ->
                suspend fun waitFor(milliseconds: Long) {
                    val end = System.nanoTime() + milliseconds * 1_000_000

                    while (System.nanoTime() < end) {
                        scene.render(System.nanoTime())
                        delay(20)
                    }
                }

                waitFor(KEEP_WINDOW_DELAY_MS + 300)
                state.size = DpSize(80.dp, 28.dp)
                waitFor(KEEP_WINDOW_DELAY_MS + 300)
                isOpen = false
                waitFor(60)

                assertEquals(SavedWindow(WindowMode.FLOATING, ON_SCREEN), stored)
                verify(exactly = 0) { repository.savedWindow = any() }
            }
        } finally {
            window.dispose()
        }
    }

    @Test
    fun `a window that shows after a while is kept from then on, and as Leaf quits`(): Unit =
        runBlocking(Dispatchers.Swing) {
            assumeFalse(GraphicsEnvironment.isHeadless(), "A frame needs a display")
            val window = TestFrame(isShown = false)
            val placements = WindowPlacements(repository)
            val state = windowStateOf(WindowOpening(WindowMode.FLOATING, ON_SCREEN))
            var isOpen by mutableStateOf(true)

            try {
                window.bounds = Rectangle(0, 3, 80, 28)

                ImageComposeScene(width = 100, height = 100, coroutineContext = Dispatchers.Swing) {
                    if (isOpen) KeepWindowPlacement(window, state, placements)
                }.use { scene ->
                    suspend fun waitFor(milliseconds: Long) {
                        val end = System.nanoTime() + milliseconds * 1_000_000

                        while (System.nanoTime() < end) {
                            scene.render(System.nanoTime())
                            delay(20)
                        }
                    }

                    waitFor(KEEP_WINDOW_DELAY_MS + 300)
                    verify(exactly = 0) { repository.savedWindow = any() }

                    // Shown, at the bounds that Compose's state already had, so nothing in the state changes
                    window.bounds = ON_SCREEN.toRectangle()
                    window.isShown = true
                    waitFor(KEEP_WINDOW_DELAY_MS / 5 + 100)
                    assertEquals(SavedWindow(WindowMode.FLOATING, ON_SCREEN), stored)

                    // As Leaf quits, the window no longer counts as showing, but keeps its bounds
                    val moved = ON_SCREEN.copy(x = 260)
                    window.bounds = moved.toRectangle()
                    window.isShown = false
                    isOpen = false
                    waitFor(60)

                    assertEquals(SavedWindow(WindowMode.FLOATING, moved), stored)
                }
            } finally {
                window.dispose()
            }
        }
}

/** A frame that never opens, which says it shows while [isShown], as a window does once the system has shown it. */
private class TestFrame(var isShown: Boolean = true) : Frame() {
    override fun isShowing(): Boolean = isShown
}

private fun WindowRect.toRectangle() = Rectangle(x, y, width, height)
