// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// A MacBook's screen without its menu bar, and a monitor to its left, without the menu bar's height either
private val LAPTOP = WindowRect(x = 0, y = 25, width = 1512, height = 957)
private val MONITOR_LEFT = WindowRect(x = -2560, y = -400, width = 2560, height = 1415)

private val ON_LAPTOP = WindowRect(x = 100, y = 80, width = 1200, height = 760)

class SavedWindowTest {
    @Test
    fun `the first run opens maximized at the system's default place and size`() {
        assertEquals(WindowOpening(WindowMode.MAXIMIZED, floating = null), windowOpening(null, listOf(LAPTOP)))
    }

    @Test
    fun `a floating window opens where it was`() {
        val saved = SavedWindow(WindowMode.FLOATING, ON_LAPTOP)

        assertEquals(WindowOpening(WindowMode.FLOATING, ON_LAPTOP), windowOpening(saved, listOf(LAPTOP, MONITOR_LEFT)))
    }

    @Test
    fun `a maximized window opens maximized, and keeps where it floated for when it's restored`() {
        val saved = SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP)

        assertEquals(WindowOpening(WindowMode.MAXIMIZED, ON_LAPTOP), windowOpening(saved, listOf(LAPTOP)))
    }

    @Test
    fun `a window that was maximized from the start opens at the default place and size under it`() {
        val saved = SavedWindow(WindowMode.MAXIMIZED, floating = null)

        assertEquals(WindowOpening(WindowMode.MAXIMIZED, floating = null), windowOpening(saved, listOf(LAPTOP)))
    }

    @Test
    fun `a saved window opens fitted to the screens there are now, floating or maximized`() {
        val onGoneMonitor = WindowRect(x = -2000, y = -300, width = 1000, height = 700)
        val centred = WindowRect(x = 256, y = 25 + 128, width = 1000, height = 700)

        assertEquals(
            WindowOpening(WindowMode.FLOATING, centred),
            windowOpening(SavedWindow(WindowMode.FLOATING, onGoneMonitor), listOf(LAPTOP)),
        )
        assertEquals(
            WindowOpening(WindowMode.MAXIMIZED, centred),
            windowOpening(SavedWindow(WindowMode.MAXIMIZED, onGoneMonitor), listOf(LAPTOP)),
        )
    }

    @Test
    fun `a window on a second screen opens there, at negative coordinates too`() {
        val onMonitor = WindowRect(x = -2000, y = -300, width = 1600, height = 1000)

        assertEquals(onMonitor, fittedToScreens(onMonitor, listOf(LAPTOP, MONITOR_LEFT)))
    }

    @Test
    fun `a window on a monitor that was unplugged opens centred on the main screen`() {
        val onMonitor = WindowRect(x = -2000, y = -300, width = 1000, height = 700)

        assertEquals(
            WindowRect(x = 256, y = 25 + 128, width = 1000, height = 700),
            fittedToScreens(onMonitor, listOf(LAPTOP)),
        )
    }

    @Test
    fun `a window larger than the main screen it moves to is shrunk to the screen's usable area`() {
        val onMonitor = WindowRect(x = -2400, y = -350, width = 2200, height = 1300)

        assertEquals(LAPTOP, fittedToScreens(onMonitor, listOf(LAPTOP)))
    }

    @Test
    fun `a window larger than its screen is shrunk to it, and moved onto it`() {
        val tooLarge = WindowRect(x = 40, y = 60, width = 2560, height = 1400)

        assertEquals(LAPTOP, fittedToScreens(tooLarge, listOf(LAPTOP)))
    }

    @Test
    fun `a window that is only a little too wide is shrunk to the screen and keeps its place when it can`() {
        val tooWide = WindowRect(x = 0, y = 200, width = 1600, height = 500)

        assertEquals(WindowRect(x = 0, y = 200, width = 1512, height = 500), fittedToScreens(tooWide, listOf(LAPTOP)))
    }

    @Test
    fun `a window partly past the right edge moves left, wholly onto the screen`() {
        val pastRight = WindowRect(x = 1000, y = 100, width = 800, height = 600)

        assertEquals(
            WindowRect(x = 1512 - 800, y = 100, width = 800, height = 600),
            fittedToScreens(pastRight, listOf(LAPTOP)),
        )
    }

    @Test
    fun `a window partly below the bottom moves up`() {
        val pastBottom = WindowRect(x = 100, y = 700, width = 800, height = 600)

        assertEquals(
            WindowRect(x = 100, y = 25 + 957 - 600, width = 800, height = 600),
            fittedToScreens(pastBottom, listOf(LAPTOP)),
        )
    }

    @Test
    fun `a window that reaches above the usable area moves down, so its title bar is on the screen`() {
        val underMenuBar = WindowRect(x = 100, y = 5, width = 800, height = 600)

        assertEquals(WindowRect(x = 100, y = 25, width = 800, height = 600), fittedToScreens(underMenuBar, listOf(LAPTOP)))
    }

    @Test
    fun `a window past the left, right or bottom edge by its invisible borders keeps its place`() {
        // Windows' invisible resize borders: a window put flush against the left edge is at -7
        val flushLeft = WindowRect(x = -7, y = 25, width = 800, height = 600)
        val atTheAllowance = WindowRect(x = 1512 - 800 + EDGE_ALLOWANCE, y = 25 + 957 - 600 + EDGE_ALLOWANCE, 800, 600)

        assertEquals(flushLeft, fittedToScreens(flushLeft, listOf(LAPTOP)))
        assertEquals(atTheAllowance, fittedToScreens(atTheAllowance, listOf(LAPTOP)))
        assertEquals(
            WindowRect(x = -EDGE_ALLOWANCE, y = 25, width = 800, height = 600),
            fittedToScreens(WindowRect(x = -EDGE_ALLOWANCE, y = 25, width = 800, height = 600), listOf(LAPTOP)),
        )
    }

    @Test
    fun `a window further past an edge than the allowance moves wholly onto the screen`() {
        val pastLeft = WindowRect(x = -EDGE_ALLOWANCE - 1, y = 100, width = 800, height = 600)
        val pastRight = WindowRect(x = 1512 - 800 + EDGE_ALLOWANCE + 1, y = 100, width = 800, height = 600)
        val pastBottom = WindowRect(x = 100, y = 25 + 957 - 600 + EDGE_ALLOWANCE + 1, width = 800, height = 600)

        assertEquals(WindowRect(x = 0, y = 100, width = 800, height = 600), fittedToScreens(pastLeft, listOf(LAPTOP)))
        assertEquals(WindowRect(x = 712, y = 100, width = 800, height = 600), fittedToScreens(pastRight, listOf(LAPTOP)))
        assertEquals(WindowRect(x = 100, y = 382, width = 800, height = 600), fittedToScreens(pastBottom, listOf(LAPTOP)))
    }

    @Test
    fun `a window above the usable area by even a point moves down`() {
        val onePointUp = WindowRect(x = 100, y = 24, width = 800, height = 600)

        assertEquals(WindowRect(x = 100, y = 25, width = 800, height = 600), fittedToScreens(onePointUp, listOf(LAPTOP)))
    }

    @Test
    fun `a window across two screens opens on the one that had most of it`() {
        // 300 of its 1000 points on the monitor, the rest on the laptop
        val across = WindowRect(x = -300, y = 100, width = 1000, height = 600)

        assertEquals(
            WindowRect(x = 0, y = 100, width = 1000, height = 600),
            fittedToScreens(across, listOf(LAPTOP, MONITOR_LEFT)),
        )

        // 700 on the monitor
        val mostlyOnMonitor = WindowRect(x = -700, y = 100, width = 1000, height = 600)

        assertEquals(
            WindowRect(x = -1000, y = 100, width = 1000, height = 600),
            fittedToScreens(mostlyOnMonitor, listOf(LAPTOP, MONITOR_LEFT)),
        )
    }

    @Test
    fun `a window that two screens share equally opens on the main screen`() {
        val left = WindowRect(x = 0, y = 0, width = 1000, height = 800)
        val right = WindowRect(x = 1000, y = 0, width = 1000, height = 800)
        val inTheMiddle = WindowRect(x = 700, y = 100, width = 600, height = 400)

        assertEquals(
            WindowRect(x = 1000 - 600, y = 100, width = 600, height = 400),
            fittedToScreens(inTheMiddle, listOf(left, right)),
        )
        assertEquals(
            WindowRect(x = 1000, y = 100, width = 600, height = 400),
            fittedToScreens(inTheMiddle, listOf(right, left)),
        )
    }

    @Test
    fun `a window that only touches a screen's edge counts as on no screen`() {
        val touching = WindowRect(x = 1512, y = 100, width = 400, height = 300)

        assertEquals(
            WindowRect(x = (1512 - 400) / 2, y = 25 + (957 - 300) / 2, width = 400, height = 300),
            fittedToScreens(touching, listOf(LAPTOP)),
        )
    }

    @Test
    fun `without screens, or with none that can be one, the window stays as it was`() {
        val anywhere = WindowRect(x = 5000, y = 5000, width = 400, height = 300)

        assertEquals(anywhere, fittedToScreens(anywhere, emptyList()))
        assertEquals(anywhere, fittedToScreens(anywhere, listOf(WindowRect(0, 0, 0, 0))))
    }

    @Test
    fun `a screen that can't be one is passed over`() {
        val broken = WindowRect(0, 0, width = -100, height = 600)
        val onLaptop = WindowRect(x = 3000, y = 3000, width = 400, height = 300)

        assertEquals(
            WindowRect(x = (1512 - 400) / 2, y = 25 + (957 - 300) / 2, width = 400, height = 300),
            fittedToScreens(onLaptop, listOf(broken, LAPTOP)),
        )
    }

    @Test
    fun `on random screens, a window opens on one screen, no larger than it, and unchanged when it was on it`() {
        val random = Random(12)

        repeat(5_000) {
            val screens = List(random.nextInt(1, 4)) {
                WindowRect(
                    x = random.nextInt(-4000, 4000),
                    y = random.nextInt(-2000, 2000),
                    width = random.nextInt(320, 3000),
                    height = random.nextInt(240, 2000),
                )
            }
            val window = WindowRect(
                x = random.nextInt(-6000, 6000),
                y = random.nextInt(-4000, 4000),
                width = random.nextInt(1, 4000),
                height = random.nextInt(1, 3000),
            )

            val fitted = fittedToScreens(window, screens)
            val screen = screens.firstOrNull { it.contains(fitted) }

            assertTrue(screen != null, "$window on $screens gave $fitted")
            assertEquals(minOf(window.width, screen!!.width), fitted.width, "$window on $screens")
            assertEquals(minOf(window.height, screen.height), fitted.height, "$window on $screens")

            if (screens.any { it.containsExactly(window) }) {
                assertEquals(window, fitted, "$window on $screens")
            }

            // A window that had to move is wholly on the screen's usable area
            if (fitted.x != window.x || fitted.y != window.y) {
                if (fitted.x != window.x) assertTrue(fitted.x >= screen.x && fitted.right <= screen.right, "$window on $screens")
                if (fitted.y != window.y) assertTrue(fitted.y >= screen.y && fitted.bottom <= screen.bottom, "$window on $screens")
            }
        }
    }

    @Test
    fun `a floating window keeps its bounds`() {
        val shown = ShownWindow(ShownPlacement.FLOATING, isMinimized = false, bounds = ON_LAPTOP)

        assertEquals(SavedWindow(WindowMode.FLOATING, ON_LAPTOP), null.after(shown))
        assertEquals(
            SavedWindow(WindowMode.FLOATING, ON_LAPTOP),
            SavedWindow(WindowMode.MAXIMIZED, WindowRect(0, 0, 10, 10)).after(shown),
        )
    }

    @Test
    fun `a maximized window keeps the bounds it had while it floated`() {
        val maximized = ShownWindow(ShownPlacement.MAXIMIZED, isMinimized = false, bounds = LAPTOP)

        assertEquals(SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP), SavedWindow(WindowMode.FLOATING, ON_LAPTOP).after(maximized))
        assertEquals(SavedWindow(WindowMode.MAXIMIZED, floating = null), null.after(maximized))
    }

    @Test
    fun `a minimized window changes nothing, at Windows' -32000 too`() {
        val saved = SavedWindow(WindowMode.FLOATING, ON_LAPTOP)
        val minimized = ShownWindow(
            ShownPlacement.FLOATING,
            isMinimized = true,
            bounds = WindowRect(x = -32_000, y = -32_000, width = 160, height = 28),
        )

        assertEquals(saved, saved.after(minimized))
        assertEquals(saved, saved.after(minimized.copy(placement = ShownPlacement.MAXIMIZED)))
        assertNull(null.after(minimized))
    }

    @Test
    fun `full screen changes nothing, so the window opens as it was before it went full screen`() {
        val fullScreen = ShownWindow(ShownPlacement.FULL_SCREEN, isMinimized = false, bounds = WindowRect(0, 0, 1512, 982))

        val floated = SavedWindow(WindowMode.FLOATING, ON_LAPTOP)
        val maximized = SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP)

        assertEquals(floated, floated.after(fullScreen))
        assertEquals(maximized, maximized.after(fullScreen))
        assertNull(null.after(fullScreen))
    }

    @Test
    fun `a floating window whose bounds can't be a window changes nothing`() {
        val saved = SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP)
        val empty = ShownWindow(ShownPlacement.FLOATING, isMinimized = false, bounds = WindowRect(10, 10, 0, 0))

        assertEquals(saved, saved.after(empty))
    }

    @Test
    fun `the saved window reads back from its stored values`() {
        assertEquals(
            SavedWindow(WindowMode.FLOATING, ON_LAPTOP),
            savedWindowOf("floating", ON_LAPTOP.x, ON_LAPTOP.y, ON_LAPTOP.width, ON_LAPTOP.height),
        )
        assertEquals(
            SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP),
            savedWindowOf("maximized", ON_LAPTOP.x, ON_LAPTOP.y, ON_LAPTOP.width, ON_LAPTOP.height),
        )
        assertEquals(SavedWindow(WindowMode.MAXIMIZED, floating = null), savedWindowOf("maximized", null, null, null, null))
    }

    @Test
    fun `nothing stored is no saved window`() {
        assertNull(savedWindowOf(null, null, null, null, null))
        assertNull(savedWindowOf("fullscreen", null, null, null, null))
        assertNull(savedWindowOf(null, 0, 0, 0, 0))
    }

    @Test
    fun `a mode that this version doesn't know reads as maximized`() {
        assertEquals(
            SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP),
            savedWindowOf("fullscreen", ON_LAPTOP.x, ON_LAPTOP.y, ON_LAPTOP.width, ON_LAPTOP.height),
        )
        assertEquals(
            SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP),
            savedWindowOf(null, ON_LAPTOP.x, ON_LAPTOP.y, ON_LAPTOP.width, ON_LAPTOP.height),
        )
        assertEquals(
            SavedWindow(WindowMode.MAXIMIZED, ON_LAPTOP),
            savedWindowOf("Floating", ON_LAPTOP.x, ON_LAPTOP.y, ON_LAPTOP.width, ON_LAPTOP.height),
        )
    }

    @Test
    fun `bounds that are missing a value or can't be a window read as none`() {
        assertEquals(SavedWindow(WindowMode.FLOATING, null), savedWindowOf("floating", 10, 10, 800, null))
        assertEquals(SavedWindow(WindowMode.FLOATING, null), savedWindowOf("floating", null, 10, 800, 600))
        assertEquals(SavedWindow(WindowMode.FLOATING, null), savedWindowOf("floating", 10, 10, 0, 600))
        assertEquals(SavedWindow(WindowMode.FLOATING, null), savedWindowOf("floating", 10, 10, 800, -1))
        assertEquals(SavedWindow(WindowMode.FLOATING, null), savedWindowOf("floating", 10, 10, MAX_WINDOW_SIZE + 1, 600))
        assertEquals(SavedWindow(WindowMode.FLOATING, null), savedWindowOf("floating", 10, 10, 800, MAX_WINDOW_SIZE + 1))
        assertEquals(
            SavedWindow(WindowMode.FLOATING, null),
            savedWindowOf("floating", MAX_WINDOW_COORDINATE + 1, 10, 800, 600),
        )
        assertEquals(
            SavedWindow(WindowMode.FLOATING, null),
            savedWindowOf("floating", 10, -MAX_WINDOW_COORDINATE - 1, 800, 600),
        )
    }

    @Test
    fun `the largest plausible values still read`() {
        val largest = WindowRect(-MAX_WINDOW_COORDINATE, MAX_WINDOW_COORDINATE, MAX_WINDOW_SIZE, MAX_WINDOW_SIZE)

        assertEquals(
            SavedWindow(WindowMode.FLOATING, largest),
            savedWindowOf("floating", largest.x, largest.y, largest.width, largest.height),
        )
        assertEquals(
            SavedWindow(WindowMode.FLOATING, WindowRect(0, 0, 1, 1)),
            savedWindowOf("floating", 0, 0, 1, 1),
        )
    }

    @Test
    fun `each mode reads back from its stored name`() {
        WindowMode.entries.forEach { assertEquals(it, WindowMode.ofStoredName(it.storedName)) }
        assertEquals(listOf("floating", "maximized"), WindowMode.entries.map { it.storedName })
    }

    @Test
    fun `the overlap of two rectangles`() {
        assertEquals(100L * 50, WindowRect(0, 0, 200, 100).overlapArea(WindowRect(100, 50, 200, 100)))
        assertEquals(0L, WindowRect(0, 0, 100, 100).overlapArea(WindowRect(100, 0, 100, 100)))
        assertEquals(0L, WindowRect(0, 0, 100, 100).overlapArea(WindowRect(0, 100, 100, 100)))
        assertEquals(0L, WindowRect(0, 0, 100, 100).overlapArea(WindowRect(500, 500, 100, 100)))
        // Large enough to overflow an Int
        assertEquals(100_000L * 100_000, WindowRect(0, 0, 100_000, 100_000).overlapArea(WindowRect(0, 0, 100_000, 100_000)))
    }
}

/** Whether [window] lies on this screen within the allowance that [fittedToScreens] gives. */
private fun WindowRect.contains(window: WindowRect): Boolean =
    window.x >= x - EDGE_ALLOWANCE &&
            window.right <= right + EDGE_ALLOWANCE &&
            window.y >= y &&
            window.bottom <= bottom + EDGE_ALLOWANCE

private fun WindowRect.containsExactly(window: WindowRect): Boolean =
    window.x >= x && window.right <= right && window.y >= y && window.bottom <= bottom
