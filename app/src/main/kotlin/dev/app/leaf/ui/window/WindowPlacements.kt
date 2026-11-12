// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.window.SavedWindow
import dev.app.leaf.domain.window.ShownPlacement
import dev.app.leaf.domain.window.ShownWindow
import dev.app.leaf.domain.window.WindowMode
import dev.app.leaf.domain.window.WindowOpening
import dev.app.leaf.domain.window.WindowRect
import dev.app.leaf.domain.window.after
import dev.app.leaf.domain.window.windowOpening
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import javax.inject.Inject
import javax.inject.Singleton

/** The window's size when Leaf has none of its own to open with, at the place that the system picks. */
val DEFAULT_WINDOW_SIZE = DpSize(1280.dp, 720.dp)

/**
 * How long the window has to stay as it is before it's kept: moving or resizing it, and the system's animations, change
 * it many times a second, and only where it ends up counts.
 */
const val KEEP_WINDOW_DELAY_MS = 500L

/**
 * Where Leaf's window opens, and keeping where it is (fork-only, 2026-11-12): in the prefs node, as the pane widths, so
 * that Leaf opens as it was when it closed. The rules are `domain/window/SavedWindow.kt`'s.
 */
@Singleton
class WindowPlacements @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) {
    private var saved: SavedWindow? = null
    private var isRead = false

    /** How to open the window on [screens], the usable areas of the screens there are now, the main screen first. */
    fun opening(screens: List<WindowRect>): WindowOpening = windowOpening(current(), screens)

    /** Keeps what [shown] says of the window, when it changes what was kept. */
    fun keep(shown: ShownWindow) {
        val before = current()
        val next = before.after(shown)

        if (next != before) {
            saved = next
            appSettingsRepository.savedWindow = next
        }
    }

    private fun current(): SavedWindow? {
        if (!isRead) {
            saved = appSettingsRepository.savedWindow
            isRead = true
        }

        return saved
    }
}

/**
 * The usable area of each screen, without the menu bar, the Dock or the taskbar, the main screen first, as AWT gives
 * them (in the units of a window's bounds). None without a display.
 */
fun usableScreenAreas(): List<WindowRect> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()

    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val main = environment.defaultScreenDevice
    val toolkit = Toolkit.getDefaultToolkit()

    return (listOf(main) + environment.screenDevices.filter { it != main }).map { device ->
        val configuration = device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = toolkit.getScreenInsets(configuration)

        WindowRect(
            x = bounds.x + insets.left,
            y = bounds.y + insets.top,
            width = bounds.width - insets.left - insets.right,
            height = bounds.height - insets.top - insets.bottom,
        )
    }
}

/** The state that Compose opens the window with, for [opening]. */
fun windowStateOf(opening: WindowOpening): WindowState {
    val floating = opening.floating

    return WindowState(
        placement = when (opening.mode) {
            WindowMode.FLOATING -> WindowPlacement.Floating
            WindowMode.MAXIMIZED -> WindowPlacement.Maximized
        },
        position = if (floating == null) {
            WindowPosition.PlatformDefault
        } else {
            WindowPosition(floating.x.dp, floating.y.dp)
        },
        size = if (floating == null) DEFAULT_WINDOW_SIZE else DpSize(floating.width.dp, floating.height.dp),
    )
}

/** The window as [placement], [isMinimized] and its [bounds] say, for [WindowPlacements.keep]. */
fun shownWindow(placement: WindowPlacement, isMinimized: Boolean, bounds: Rectangle) = ShownWindow(
    placement = when (placement) {
        WindowPlacement.Floating -> ShownPlacement.FLOATING
        WindowPlacement.Maximized -> ShownPlacement.MAXIMIZED
        WindowPlacement.Fullscreen -> ShownPlacement.FULL_SCREEN
    },
    isMinimized = isMinimized,
    bounds = WindowRect(bounds.x, bounds.y, bounds.width, bounds.height),
)

/**
 * Keeps where [window] is, with [placements]: [KEEP_WINDOW_DELAY_MS] after it last changed, and when it closes, as Leaf
 * quits. Compose's [state] says when it changes, and how it's shown; the bounds are AWT's, the window's own.
 *
 * Before the window shows, its bounds are AWT's first ones (80 × 28 at the top left on macOS), not those Compose gives
 * it, so nothing is kept until it has been seen showing. As Leaf quits, the window no longer counts as showing, but it
 * keeps its bounds, so it's kept then once it was seen.
 */
@Composable
fun KeepWindowPlacement(window: Window, state: WindowState, placements: WindowPlacements) {
    val seen = remember(window) { SeenShowing() }

    fun keepNow() = placements.keep(shownWindow(state.placement, state.isMinimized, window.bounds))

    LaunchedEffect(window, state) {
        snapshotFlow { listOf(state.placement, state.isMinimized, state.size, state.position) }
            .collectLatest {
                delay(KEEP_WINDOW_DELAY_MS)

                // Compose's state already has the bounds it opens the window with, so showing changes nothing in it
                while (!window.isShowing) delay(KEEP_WINDOW_DELAY_MS / 5)

                seen.isSeen = true
                keepNow()
            }
    }

    DisposableEffect(window, state) {
        onDispose {
            if (seen.isSeen) keepNow()
        }
    }
}

private class SeenShowing {
    var isSeen = false
}
