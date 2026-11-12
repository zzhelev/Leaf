// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.window

/*
 * Where Leaf's window was when Leaf last ran, and where to open it again (fork-only, 2026-11-12). Rectangles are in the
 * units that Java's AWT gives windows and screens: points on macOS, and pixels at 100% scaling on Windows and Linux.
 */

/**
 * How far a window may reach past the left, right and bottom edges of its screen's usable area and still open where it
 * was. Windows counts a window's invisible resize borders, about 7 pixels at 100%, in its bounds, so a window put flush
 * against an edge is a little past it. None at the top, so that the title bar always opens on the screen. A window that
 * has to move goes wholly on the area.
 */
const val EDGE_ALLOWANCE = 16

/** The largest width or height that a saved window can have; anything larger comes from a damaged file. */
const val MAX_WINDOW_SIZE = 100_000

/** How far from the origin a saved window can be; Windows puts a minimized window at -32,000. */
const val MAX_WINDOW_COORDINATE = 1_000_000

data class WindowRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    /** Whether this can be a window: a size of at least a pixel, and nothing a damaged file would give. */
    val isPlausible: Boolean
        get() = width in 1..MAX_WINDOW_SIZE &&
                height in 1..MAX_WINDOW_SIZE &&
                x in -MAX_WINDOW_COORDINATE..MAX_WINDOW_COORDINATE &&
                y in -MAX_WINDOW_COORDINATE..MAX_WINDOW_COORDINATE

    /** The area that this and [other] share, 0 when they don't meet. */
    fun overlapArea(other: WindowRect): Long {
        val width = minOf(right, other.right) - maxOf(x, other.x)
        val height = minOf(bottom, other.bottom) - maxOf(y, other.y)

        return if (width <= 0 || height <= 0) 0 else width.toLong() * height
    }
}

/** The ways that Leaf opens its window. Full screen isn't one: see [after]. */
enum class WindowMode(val storedName: String) {
    FLOATING("floating"),
    MAXIMIZED("maximized");

    companion object {
        fun ofStoredName(name: String?): WindowMode? = entries.firstOrNull { it.storedName == name }
    }
}

/**
 * What Leaf keeps between runs: how the window was ([mode]), and where it last was while it floated ([floating]), which a
 * maximized window goes back to when it's restored. [floating] is null until the window has floated.
 */
data class SavedWindow(val mode: WindowMode, val floating: WindowRect?)

/** How the window is shown now, as the toolkit says. */
enum class ShownPlacement { FLOATING, MAXIMIZED, FULL_SCREEN }

data class ShownWindow(val placement: ShownPlacement, val isMinimized: Boolean, val bounds: WindowRect)

/** How to open the window. [floating] null means the default size, at the place that the system picks. */
data class WindowOpening(val mode: WindowMode, val floating: WindowRect?)

/**
 * The saved window from the values in the preferences, any of which may be missing, or be from another version or a
 * damaged file. A mode that this version doesn't know reads as maximized, Leaf's first-run default, and bounds that can't
 * be a window as none. Nothing usable is no saved window.
 */
fun savedWindowOf(mode: String?, x: Int?, y: Int?, width: Int?, height: Int?): SavedWindow? {
    val floating = if (x != null && y != null && width != null && height != null) {
        WindowRect(x, y, width, height).takeIf { it.isPlausible }
    } else {
        null
    }
    val knownMode = WindowMode.ofStoredName(mode)

    if (knownMode == null && floating == null) return null

    return SavedWindow(knownMode ?: WindowMode.MAXIMIZED, floating)
}

/**
 * What to keep once the window is shown as [shown]. A floating window keeps its bounds, and a maximized one the bounds it
 * had while it floated. A minimized window changes nothing (Windows moves it to -32,000), and neither does full screen,
 * which Leaf doesn't open in: the window opens as it was before it went full screen.
 */
fun SavedWindow?.after(shown: ShownWindow): SavedWindow? = when {
    shown.isMinimized -> this
    shown.placement == ShownPlacement.FULL_SCREEN -> this
    shown.placement == ShownPlacement.MAXIMIZED -> SavedWindow(WindowMode.MAXIMIZED, this?.floating)
    !shown.bounds.isPlausible -> this
    else -> SavedWindow(WindowMode.FLOATING, shown.bounds)
}

/**
 * How to open the window from [saved], on [screens]: the usable area of each screen (without the menu bar, the Dock or
 * the taskbar), the main screen first. The first run opens maximized. A saved floating rectangle is fitted to the
 * screens as [fittedToScreens] says, also for a maximized window, which is maximized on the screen that it then lies
 * on, and goes back there when it's restored.
 */
fun windowOpening(saved: SavedWindow?, screens: List<WindowRect>): WindowOpening {
    if (saved == null) return WindowOpening(WindowMode.MAXIMIZED, floating = null)

    return WindowOpening(saved.mode, saved.floating?.let { fittedToScreens(it, screens) })
}

/**
 * Where [window] opens on [screens] (usable areas, the main screen first). It goes on the screen that it overlaps most,
 * no larger than its usable area. Across and down apart, it keeps its place where it lies on that area, or within
 * [EDGE_ALLOWANCE] past its left, right or bottom edge, and otherwise moves the least that puts it wholly on the area. On
 * no screen, as after a monitor was unplugged, it's centred on the main screen. Without screens it stays as it is. A
 * window that spanned two screens opens on the one that had most of it.
 */
fun fittedToScreens(window: WindowRect, screens: List<WindowRect>): WindowRect {
    val usable = screens.filter { it.isPlausible }
    if (usable.isEmpty()) return window

    // The first of the largest, so the main screen wins a tie, and is the screen when the window is on none
    val screen = usable.maxBy { window.overlapArea(it) }
    val isOnAScreen = window.overlapArea(screen) > 0

    val width = window.width.coerceAtMost(screen.width)
    val height = window.height.coerceAtMost(screen.height)

    return if (isOnAScreen) {
        val x = if (window.x in screen.x - EDGE_ALLOWANCE..screen.right - width + EDGE_ALLOWANCE) {
            window.x
        } else {
            window.x.coerceIn(screen.x, screen.right - width)
        }
        val y = if (window.y in screen.y..screen.bottom - height + EDGE_ALLOWANCE) {
            window.y
        } else {
            window.y.coerceIn(screen.y, screen.bottom - height)
        }

        WindowRect(x, y, width, height)
    } else {
        WindowRect(
            x = screen.x + (screen.width - width) / 2,
            y = screen.y + (screen.height - height) / 2,
            width = width,
            height = height,
        )
    }
}
