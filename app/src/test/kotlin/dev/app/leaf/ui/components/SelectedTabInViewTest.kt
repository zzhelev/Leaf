// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.tabs.TabLook
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.frameColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The selected tab shows whole in the tab bar, however it was selected (upstream issue 368). */
class SelectedTabInViewTest {
    @Test
    fun `an item already whole in the viewport needs no scroll`() {
        assertEquals(0, revealScrollDelta(itemStart = 10, itemEnd = 60, viewportStart = 0, viewportEnd = 100))
        assertEquals(0, revealScrollDelta(itemStart = 0, itemEnd = 100, viewportStart = 0, viewportEnd = 100))
    }

    @Test
    fun `an item cut at an edge scrolls just far enough to show whole`() {
        assertEquals(-15, revealScrollDelta(itemStart = -15, itemEnd = 35, viewportStart = 0, viewportEnd = 100))
        assertEquals(20, revealScrollDelta(itemStart = 70, itemEnd = 120, viewportStart = 0, viewportEnd = 100))
    }

    @Test
    fun `an item longer than the viewport lines up with its start`() {
        assertEquals(30, revealScrollDelta(itemStart = 30, itemEnd = 180, viewportStart = 0, viewportEnd = 100))
        assertEquals(-30, revealScrollDelta(itemStart = -30, itemEnd = 180, viewportStart = 0, viewportEnd = 100))
    }

    @Test
    fun `in the title bar, stepping through every tab and back to the first keeps the selected one whole`(): Unit =
        runBlocking(Dispatchers.Swing) {
            TabsScene(tabCount = 12, width = 836, inTitleBar = true).use { scene ->
                scene.settle()

                for (index in 1 until 12) {
                    scene.select(index)
                    scene.assertSelectedWhole()
                }

                // Wraps round, as Ctrl+Tab does from the last tab
                scene.select(0)
                scene.assertSelectedWhole()
            }
        }

    @Test
    fun `in the plain tab row, stepping through every tab keeps the selected one whole`(): Unit =
        runBlocking(Dispatchers.Swing) {
            TabsScene(tabCount = 9, width = 700, inTitleBar = false).use { scene ->
                scene.settle()

                for (index in 1 until 9) {
                    scene.select(index)
                    scene.assertSelectedWhole()
                }

                scene.select(0)
                scene.assertSelectedWhole()
            }
        }

    @Test
    fun `a tab far out of sight jumps into view at the edge it was beyond`(): Unit = runBlocking(Dispatchers.Swing) {
        TabsScene(tabCount = 12, width = 836, inTitleBar = true).use { scene ->
            scene.settle()

            scene.select(9)
            scene.assertSelectedWhole()
            val viewport = scene.viewport()
            assertEquals(viewport.right, scene.tabBounds(9).right, 1f, "it comes in at the end")

            scene.select(1)
            scene.assertSelectedWhole()
            assertEquals(viewport.left, scene.tabBounds(1).left, 1f, "it comes in at the start")
        }
    }

    @Test
    fun `selecting a tab already in view leaves the bar where it is`(): Unit = runBlocking(Dispatchers.Swing) {
        TabsScene(tabCount = 12, width = 836, inTitleBar = true).use { scene ->
            scene.settle()
            val before = scene.tabBounds(2)

            scene.select(2)

            assertEquals(before, scene.tabBounds(2))
        }
    }

    @Test
    fun `a tab added and selected past the end shows whole`(): Unit = runBlocking(Dispatchers.Swing) {
        TabsScene(tabCount = 12, width = 836, inTitleBar = true).use { scene ->
            scene.settle()

            scene.addAndSelect()

            scene.assertSelectedWhole()
        }
    }

    private class FakeTab(name: String) : TabInformationProvider {
        override val tabName = MutableStateFlow<String?>(name)
        override val worktreeName = MutableStateFlow<String?>(null)
        override val extraInfo = MutableStateFlow<String?>("/repos/$name")
        override val tabLook = MutableStateFlow<TabLook?>(TabLook())
        override fun renameTab(typed: String) {}
        override fun resetTabName() {}
        override fun setTabColor(color: Int?) {}
        override fun dispose() {}
    }

    private class TabsScene(tabCount: Int, private val width: Int, inTitleBar: Boolean) : AutoCloseable {
        private val tabs = mutableStateListOf<TabInformation<FakeTab>>().apply {
            addAll(List(tabCount) { TabInformation(FakeTab("tab $it")) })
        }
        private val current = mutableStateOf(tabs.first())

        private val scene = ImageComposeScene(
            width = width + 200,
            height = 60,
            coroutineContext = Dispatchers.Swing,
        ) {
            AppTheme(Theme.Light) {
                Box(Modifier.background(MaterialTheme.frameColors.background).fillMaxSize()) {
                    Box(Modifier.width(width.dp).height(40.dp), contentAlignment = Alignment.CenterStart) {
                        TabsRow(
                            tabs = tabs,
                            defaultTabName = "New tab",
                            currentTab = current.value,
                            onTabSelected = { current.value = it },
                            onTabClosed = {},
                            onMoveTab = { _, _ -> },
                            onAddNewTab = {},
                            titleBarWidth = if (inTitleBar) width.dp else null,
                        )
                    }
                }
            }
        }

        suspend fun settle() {
            repeat(12) {
                scene.render(System.nanoTime())
                delay(25)
            }
        }

        suspend fun select(index: Int) {
            current.value = tabs[index]
            settle()
        }

        suspend fun addAndSelect() {
            val tab = TabInformation(FakeTab("tab ${tabs.size}"))
            tabs.add(tab)
            current.value = tab
            settle()
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            visit(scene.semanticsOwners.first().rootSemanticsNode)

            return nodes
        }

        private fun SemanticsNode.unclippedBounds(): Rect {
            val position = positionInRoot
            return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
        }

        /** Where the tabs scroll: the tab row's scrolling list. */
        fun viewport(): Rect = nodes()
            .single { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
            .unclippedBounds()

        /** Where the tab is laid out, whether it shows or not. */
        fun tabBounds(index: Int): Rect {
            val title = nodes().single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == "tab $index"
            }
            val tab = generateSequence(title) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }

            return tab.unclippedBounds()
        }

        fun assertSelectedWhole() {
            val index = tabs.indexOf(current.value)
            val tab = tabBounds(index)
            val viewport = viewport()

            assertTrue(
                tab.left >= viewport.left - 0.5f && tab.right <= viewport.right + 0.5f,
                "tab $index at ${tab.left}..${tab.right} shows whole in ${viewport.left}..${viewport.right}",
            )
        }

        override fun close() = scene.close()
    }
}
