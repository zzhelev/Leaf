// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.jetbrains.JBR
import dev.app.leaf.common.printError
import dev.app.leaf.ui.window.TitleBarControls
import dev.app.leaf.ui.window.WindowTitleBar
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener

private const val TAG = "LinuxWindowTitleBar"

/** Two presses this close together are a double-click, when the desktop doesn't say (X11 gives `awt.multiClickInterval`). */
private const val DEFAULT_DOUBLE_CLICK_MILLIS = 500L

/**
 * How far the pointer moves with the button down, in the window's pixels, before the window moves. A press alone
 * doesn't move it: the window manager takes the pointer then, and when the button was already up, as after a quick
 * click, xfwm4 went on moving the window until the next click.
 */
private const val DRAG_THRESHOLD_PIXELS = 4f

/** Starts moving a window with the pointer, whose primary button is down. */
fun interface WindowMover {
    fun startMoving(window: Window)
}

/** JetBrains Runtime asks the window manager to move the window, so its snapping to edges and tiling keep working. */
private object JbrWindowMover : WindowMover {
    override fun startMoving(window: Window) {
        try {
            JBR.getWindowMove()?.startMovingTogetherWithMouse(window, MouseEvent.BUTTON1)
        } catch (e: Exception) {
            printError(TAG, "Couldn't move the window", e)
        }
    }
}

/**
 * The title bar of Leaf's own frame on Linux (fork-only, docs/fork/window-chrome.md, section 10). JetBrains Runtime has
 * no custom title bar there, so the window is undecorated: a drag from the empty bar moves the window through the window
 * manager, a double-click maximizes or restores it, and Leaf draws the window's buttons in the desktop's [layout].
 */
class LinuxWindowTitleBar(
    private val window: Frame,
    private val layout: WindowButtonLayout,
    private val mover: WindowMover = JbrWindowMover,
    private val nanoTime: () -> Long = System::nanoTime,
    private val doubleClickMillis: Long = desktopDoubleClickMillis(),
) : WindowTitleBar {
    private var lastPressNanos: Long? = null

    /** Where the button went down, until the drag that it may start moves the window. */
    private var pressedAt: Offset? = null

    // Leaf's buttons have room of their own in the bar's row, so the content needs none for them
    override val leftInset: Float = 0f
    override val rightInset: Float = 0f

    override fun setHeight(height: Float) {}

    override fun setButtonsDark(dark: Boolean) {}

    override fun forceHitTest(leafTakesIt: Boolean) {}

    override fun pointerOnBar(type: PointerEventType, position: Offset) {
        when (type) {
            PointerEventType.Press -> {
                val now = nanoTime()
                val last = lastPressNanos

                if (last != null && now - last <= doubleClickMillis * 1_000_000) {
                    lastPressNanos = null
                    pressedAt = null
                    window.toggleMaximized()
                } else {
                    lastPressNanos = now
                    pressedAt = position
                }
            }

            PointerEventType.Move -> {
                val start = pressedAt ?: return

                if ((position - start).getDistance() > DRAG_THRESHOLD_PIXELS) {
                    pressedAt = null
                    // A drag isn't the first half of a double-click
                    lastPressNanos = null
                    mover.startMoving(window)
                }
            }

            PointerEventType.Release -> pressedAt = null
        }
    }

    override val startButtons: (@Composable (TitleBarControls) -> Unit)? = buttonsOn(layout.start, atStart = true)
    override val endButtons: (@Composable (TitleBarControls) -> Unit)? = buttonsOn(layout.end, atStart = false)

    private fun buttonsOn(buttons: List<WindowButton>, atStart: Boolean): (@Composable (TitleBarControls) -> Unit)? =
        if (buttons.isEmpty()) {
            null
        } else {
            { controls -> WindowButtons(window, buttons, atStart, controls) }
        }
}

private fun desktopDoubleClickMillis(): Long =
    (Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int)?.toLong()
        ?.takeIf { it > 0 }
        ?: DEFAULT_DOUBLE_CLICK_MILLIS

internal fun Frame.isMaximized(): Boolean = extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH

internal fun Frame.toggleMaximized() {
    extendedState = if (isMaximized()) {
        extendedState and Frame.MAXIMIZED_BOTH.inv()
    } else {
        extendedState or Frame.MAXIMIZED_BOTH
    }
}

internal fun Frame.minimize() {
    extendedState = extendedState or Frame.ICONIFIED
}

/** Closes the window as its close button would: Compose's `onCloseRequest` answers, which may ask first. */
internal fun Window.requestClose() {
    dispatchEvent(WindowEvent(this, WindowEvent.WINDOW_CLOSING))
}

/** Whether [window] is maximized, which follows the window manager. */
@Composable
internal fun rememberIsMaximized(window: Frame): State<Boolean> {
    val isMaximized = remember(window) { mutableStateOf(window.isMaximized()) }

    DisposableEffect(window) {
        val listener = WindowStateListener { isMaximized.value = window.isMaximized() }

        window.addWindowStateListener(listener)
        isMaximized.value = window.isMaximized()

        onDispose { window.removeWindowStateListener(listener) }
    }

    return isMaximized
}
