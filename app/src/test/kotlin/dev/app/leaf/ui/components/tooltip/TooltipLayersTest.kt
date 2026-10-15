// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.tooltip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.showPopup
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val INSTANT = Offset(50f, 50f)
private val DELAYED = Offset(250f, 50f)
private val IN_LAYER = Offset(25f, 325f)
private val OUTSIDE = Offset(150f, 200f)

/** Tooltips show on hover, not beneath a dialog or a menu, and not after a click until the pointer leaves (fork-only). */
class TooltipLayersTest {
    private var layerOpen by mutableStateOf(false)
    private var cover by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** A dialog is a [TooltipLayer]; menus are focusable popups, which already keep the pointer from what's beneath. */
    @Test
    fun `dialogs and menus cover the tooltips beneath them`(): Unit = runBlocking(Dispatchers.Swing) {
        val covers = mapOf<String, @Composable () -> Unit>(
            "dialog" to { MaterialDialog { Text("A dialog") } },
            "context menu" to {
                showPopup(
                    showIcons = false,
                    x = 300,
                    y = 300,
                    contextMenuElements = listOf(ContextMenuElement.ContextTextEntry("Copy", onClick = {})),
                    onDismissRequest = {},
                )
            },
            "header menu" to { SortMenuPopup(items = listOf(SortMenuItem.Note("A note")), onDismissRequest = {}) },
        )

        TooltipScene().use { scene ->
            for ((name, content) in covers) {
                scene.move(INSTANT)
                assertTrue("instant tip" in scene.texts(), "$name: ${scene.texts()}")

                cover = content
                scene.render()
                assertFalse("instant tip" in scene.texts(), "$name: ${scene.texts()}")

                cover = null
                scene.move(OUTSIDE)
            }
        }
    }

    @Test
    fun `an instant tooltip doesn't show beneath an open layer, and the layer's own does`(): Unit =
        runBlocking(Dispatchers.Swing) {
            TooltipScene().use { scene ->
                scene.move(INSTANT)
                assertTrue("instant tip" in scene.texts(), scene.texts().toString())

                layerOpen = true
                scene.render()
                assertFalse("instant tip" in scene.texts(), scene.texts().toString())

                scene.move(IN_LAYER)
                assertTrue("layer tip" in scene.texts(), scene.texts().toString())

                layerOpen = false
                scene.move(INSTANT)
                assertTrue("instant tip" in scene.texts(), scene.texts().toString())
            }
        }

    @Test
    fun `a click hides an instant tooltip until the pointer leaves`(): Unit = runBlocking(Dispatchers.Swing) {
        TooltipScene().use { scene ->
            scene.move(INSTANT)
            scene.click(INSTANT, PointerButton.Secondary)
            assertFalse("instant tip" in scene.texts(), scene.texts().toString())

            scene.move(INSTANT + Offset(10f, 10f))
            assertFalse("instant tip" in scene.texts(), "moving inside doesn't bring it back")

            scene.move(OUTSIDE)
            scene.move(INSTANT)
            assertTrue("instant tip" in scene.texts(), scene.texts().toString())
        }
    }

    @Test
    fun `a delayed tooltip doesn't show beneath an open layer, nor after a click`(): Unit =
        runBlocking(Dispatchers.Swing) {
            TooltipScene().use { scene ->
                scene.hover(DELAYED)
                assertTrue("delayed tip" in scene.texts(), scene.texts().toString())

                layerOpen = true
                scene.render()
                assertFalse("delayed tip" in scene.texts(), scene.texts().toString())

                layerOpen = false
                scene.move(OUTSIDE)
                scene.hover(DELAYED)
                scene.click(DELAYED, PointerButton.Primary)
                scene.hover(DELAYED + Offset(10f, 10f))
                assertFalse("delayed tip" in scene.texts(), "moving inside after a click doesn't bring it back")

                scene.move(OUTSIDE)
                scene.hover(DELAYED)
                assertTrue("delayed tip" in scene.texts(), scene.texts().toString())
            }
        }

    @OptIn(ExperimentalComposeUiApi::class)
    private inner class TooltipScene : AutoCloseable {
        private val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            AppTheme {
                Box(Modifier.fillMaxSize()) {
                    InstantTooltip("instant tip") { Box(Modifier.size(100.dp)) }

                    Box(Modifier.offset(x = 200.dp)) {
                        DelayedTooltip("delayed tip") { Box(Modifier.size(100.dp)) }
                    }

                    cover?.invoke()

                    if (layerOpen) {
                        TooltipLayer {
                            Box(Modifier.offset(y = 300.dp)) {
                                InstantTooltip("layer tip") { Box(Modifier.size(50.dp)) }
                            }
                        }
                    }
                }
            }
        }

        init {
            scene.render()
        }

        /**
         * Renders two frames: a layer opens once its first frame is applied, and the tooltips follow in the next, as
         * the app's next frame comes right away.
         */
        fun render() {
            scene.render()
            scene.render()
        }

        fun move(position: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, position)
            render()
        }

        /** Moves to [position] and rests there for longer than a delayed tooltip waits. */
        suspend fun hover(position: Offset) {
            move(position)
            delay(800)
            render()
        }

        fun click(position: Offset, button: PointerButton) {
            val pressed = if (button == PointerButton.Secondary) {
                PointerButtons(isSecondaryPressed = true)
            } else {
                PointerButtons(isPrimaryPressed = true)
            }

            scene.sendPointerEvent(PointerEventType.Press, position, buttons = pressed, button = button)
            scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = button)
            render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }
}
