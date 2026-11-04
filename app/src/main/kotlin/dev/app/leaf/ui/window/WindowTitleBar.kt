// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog

private const val TAG = "WindowTitleBar"

/** The height of the title bar that holds the tabs (docs/fork/window-chrome.md). */
val TITLE_BAR_HEIGHT = 40.dp

/**
 * The window's own title bar, which Leaf draws in place of the system's (fork-only, docs/fork/window-chrome.md). Sizes
 * are in the window's units: points on macOS.
 */
interface WindowTitleBar {
    /** The room that the window's buttons take at the left. */
    val leftInset: Float

    /** The room that the window's buttons take at the right. */
    val rightInset: Float

    fun setHeight(height: Float)

    /**
     * For the mouse event being handled: true when Leaf takes it, false when the window does, which moves the window on
     * a drag and zooms it on a double-click.
     */
    fun forceHitTest(leafTakesIt: Boolean)
}

/** JetBrains Runtime's custom title bar, the one IntelliJ and Jewel use. */
private class JbrWindowTitleBar(private val window: ComposeWindow) : WindowTitleBar {
    private val decorations: WindowDecorations = JBR.getWindowDecorations()
    private val bar: WindowDecorations.CustomTitleBar = decorations.createCustomTitleBar()

    override val leftInset: Float get() = bar.leftInset
    override val rightInset: Float get() = bar.rightInset

    override fun setHeight(height: Float) {
        bar.height = height
        // The bar takes a new height when it's set on the window again
        decorations.setCustomTitleBar(window, bar)
    }

    override fun forceHitTest(leafTakesIt: Boolean) = bar.forceHitTest(leafTakesIt)
}

/**
 * [window]'s own title bar, or null where Leaf keeps the system's: on systems other than macOS (Windows comes later,
 * D1), and on a runtime other than JetBrains', which has no API for it.
 */
fun windowTitleBarOf(window: ComposeWindow): WindowTitleBar? {
    if (currentOs != OS.MAC) return null

    return try {
        if (JBR.isWindowDecorationsSupported()) {
            JbrWindowTitleBar(window)
        } else {
            printLog(TAG, "The runtime can't draw a custom title bar, so the system's stays")
            null
        }
    } catch (e: Exception) {
        printError(TAG, "Couldn't make a custom title bar, so the system's stays", e)
        null
    }
}

/** Where Leaf's controls are in the title bar, in the window's root coordinates. */
@Stable
class TitleBarControls {
    private val bounds = HashMap<Any, Rect>()

    val all: Collection<Rect> get() = bounds.values

    internal fun put(owner: Any, rect: Rect) {
        bounds[owner] = rect
    }

    internal fun remove(owner: Any) {
        bounds.remove(owner)
    }
}

/** Marks a control in the title bar: a press on it goes to Leaf, not to the window. */
fun Modifier.titleBarControl(controls: TitleBarControls): Modifier = this then TitleBarControlElement(controls)

private data class TitleBarControlElement(val controls: TitleBarControls) :
    ModifierNodeElement<TitleBarControlNode>() {
    override fun create() = TitleBarControlNode(controls)

    override fun update(node: TitleBarControlNode) {
        node.controls.remove(node)
        node.controls = controls
    }
}

private class TitleBarControlNode(var controls: TitleBarControls) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        controls.put(this, coordinates.boundsInRoot())
    }

    override fun onDetach() {
        controls.remove(this)
    }
}

/**
 * Decides who takes each mouse event over the title bar. A press on a control and everything until its release are
 * Leaf's, so that dragging a tab never moves the window; a press elsewhere is the window's.
 */
class TitleBarPointer {
    private var pressedOnControl = false

    fun leafTakes(type: PointerEventType, position: Offset, controls: Collection<Rect>): Boolean {
        val onControl = controls.any { it.contains(position) }

        return when (type) {
            PointerEventType.Press -> {
                pressedOnControl = onControl
                onControl
            }

            PointerEventType.Release -> {
                val takes = pressedOnControl || onControl
                pressedOnControl = false
                takes
            }

            else -> pressedOnControl || onControl
        }
    }
}

/**
 * The title bar: [TITLE_BAR_HEIGHT] tall, starting after the window's buttons and ending [TITLE_BAR_FREE_STRIP] before
 * the right edge. [content] marks its controls with [titleBarControl]; the rest of the bar moves the window.
 */
@Composable
fun TitleBar(
    titleBar: WindowTitleBar,
    window: ComposeWindow,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (TitleBarControls) -> Unit,
) {
    val controls = remember { TitleBarControls() }
    val pointer = remember { TitleBarPointer() }
    val holder = remember { CoordinatesHolder() }
    val density = LocalDensity.current
    // Points to pixels: the window's scale; pixels to dp: Compose's density, which Leaf's UI scale may change
    val windowScale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
    val heightInPoints = with(density) { TITLE_BAR_HEIGHT.toPx() } / windowScale

    // Not set again when full screen starts or ends: macOS resizes the window then, and setting the bar at the same
    // time made JBR drop a resize event ("invokeAndWait is discarded as the EventDispatch thread is currently blocked")
    val buttonsInset: Dp = remember(titleBar, heightInPoints, windowScale) {
        titleBar.setHeight(heightInPoints)
        printLog(TAG, "Title bar: height ${heightInPoints}pt, insets ${titleBar.leftInset} and ${titleBar.rightInset}")
        with(density) { (titleBar.leftInset * windowScale).toDp() }
    }
    // Full screen hides the window's buttons, as JBR reports (an inset of 0)
    val leftInset = if (isFullscreen) 0.dp else buttonsInset

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TITLE_BAR_HEIGHT)
            .background(MaterialTheme.colors.surface)
            .onGloballyPositioned { holder.coordinates = it }
            .pointerInput(titleBar) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val position = event.changes.firstOrNull()?.position ?: continue
                        val inRoot = holder.coordinates?.takeIf { it.isAttached }?.localToRoot(position) ?: position

                        titleBar.forceHitTest(pointer.leafTakes(event.type, inRoot, controls.all))
                    }
                }
            }
            .padding(start = leftInset, end = TITLE_BAR_FREE_STRIP),
        contentAlignment = Alignment.CenterStart,
    ) {
        content(controls)
    }
}

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}
