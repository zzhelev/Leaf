// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.tooltip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The layers over the window's content that are open (fork-only): dialogs, last the topmost. Only the topmost layer
 * shows tooltips, or the window's content while none is open.
 *
 * Leaf's dialogs draw on the window's canvas and take the pointer only inside their box, so the content beneath a
 * dialog showed its tooltips while the pointer moved over it, as after choosing a menu item that opens a dialog. Menus
 * need nothing: they're focusable popups, which keep the pointer from what's beneath them.
 */
internal object TooltipLayers {
    private val open = mutableStateListOf<Any>()

    /** Whether [layer] is the topmost one, null meaning the window's content. Reading it follows the changes. */
    fun isTop(layer: Any?): Boolean = open.lastOrNull() === layer

    fun add(layer: Any) {
        open += layer
    }

    fun remove(layer: Any) {
        open -= layer
    }
}

/** The layer that a composable is in, null for the window's content. */
internal val LocalTooltipLayer = staticCompositionLocalOf<Any?> { null }

/**
 * Marks [content] as a layer over what's beneath it while it's shown, such as a dialog: tooltips beneath it don't show,
 * and its own do.
 */
@Composable
fun TooltipLayer(content: @Composable () -> Unit) {
    val layer = remember { Any() }

    DisposableEffect(layer) {
        TooltipLayers.add(layer)
        onDispose { TooltipLayers.remove(layer) }
    }

    CompositionLocalProvider(LocalTooltipLayer provides layer, content = content)
}

/**
 * Whether a tooltip may show: its layer is the topmost, and the pointer hasn't pressed its element since it last left
 * it, as tooltips behave on macOS and Windows. A click, left or right, isn't a hover.
 */
@Stable
internal class TooltipVisibility(val layer: Any?) {
    var pressedSinceEntering by mutableStateOf(false)

    val canShow: Boolean
        get() = !pressedSinceEntering && TooltipLayers.isTop(layer)
}

/** The [TooltipVisibility] of a tooltip in the layer it's composed in. */
@Composable
internal fun rememberTooltipVisibility(): TooltipVisibility {
    val layer = LocalTooltipLayer.current

    return remember(layer) { TooltipVisibility(layer) }
}

/**
 * Notes a press on the element, before anything inside it takes it, and the pointer leaving it. Nothing is consumed: a
 * context menu still opens, and a click still clicks.
 */
internal fun Modifier.tooltipPresses(visibility: TooltipVisibility): Modifier = pointerInput(visibility) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)

            when (event.type) {
                PointerEventType.Press -> visibility.pressedSinceEntering = true
                PointerEventType.Exit -> visibility.pressedSinceEntering = false
            }
        }
    }
}
