// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.tabs.TabLook
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.frameColors
import dev.app.leaf.ui.components.TabInformation
import dev.app.leaf.ui.components.TabInformationProvider
import dev.app.leaf.ui.components.TabsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import java.io.File

/** The tabs in the window's title bar (docs/fork/window-chrome.md): they shrink, then scroll with arrows. */
class TitleBarTabsTest {
    @Test
    fun `tabs get an equal share of the bar, gaps and the + button left out, and never less than the minimum`() {
        // 836 - 32 for the + button and its gap - 4 between the two tabs
        assertEquals(400.dp, titleBarTabWidth(836.dp, 2))
        // 836 - 32 - 4 × 4 = 788, over five
        assertEquals(157.6.dp, titleBarTabWidth(836.dp, 5))
        assertEquals(TITLE_BAR_MIN_TAB_WIDTH, titleBarTabWidth(836.dp, 12))
        assertEquals(836.dp, titleBarTabWidth(836.dp, 0))
    }

    @Test
    fun `tabs fit while each can have the minimum, with the gaps between them`() {
        val sixTabs = TITLE_BAR_MIN_TAB_WIDTH * 6 + TITLE_BAR_TAB_GAP * 5 + ADD_TAB_BUTTON_WIDTH

        assertTrue(tabsFitTitleBar(sixTabs, 6))
        assertFalse(tabsFitTitleBar(sixTabs - 1.dp, 6))
        assertFalse(tabsFitTitleBar(sixTabs, 7))
        assertTrue(tabsFitTitleBar(100.dp, 0))
    }

    @Test
    fun `a few tabs keep their own width, with no arrows`(): Unit = runBlocking(Dispatchers.Swing) {
        TabsScene(tabCount = 2, barWidth = 836).use { scene ->
            scene.settle()

            assertEquals(listOf(168f, 168f), scene.tabWidths())
            assertTrue(scene.arrows().isEmpty())
        }
    }

    @Test
    fun `tabs shrink to share the bar before they scroll`(): Unit = runBlocking(Dispatchers.Swing) {
        TabsScene(tabCount = 5, barWidth = 836).use { scene ->
            scene.settle()

            assertTrue(scene.tabWidths().all { it in 157f..158f }, scene.tabWidths().toString())
            assertEquals(5, scene.tabWidths().size)
            assertTrue(scene.arrows().isEmpty())
        }
    }

    @Test
    fun `too many tabs scroll with arrows, the earlier one dimmed at the start`(): Unit =
        runBlocking(Dispatchers.Swing) {
            TabsScene(tabCount = 12, barWidth = 836).use { scene ->
                scene.settle()

                assertTrue(scene.tabWidths().all { it == 120f }, scene.tabWidths().toString())
                assertEquals(listOf("Earlier tabs" to false, "Later tabs" to true), scene.arrows())
                val arrowRight = scene.arrowRight("Later tabs")
                assertTrue(arrowRight + ADD_TAB_BUTTON_WIDTH.value <= 836f, "the arrows and + stay in the bar: $arrowRight")

                scene.clickArrow("Later tabs")
                scene.settle()

                assertEquals(listOf("Earlier tabs" to true, "Later tabs" to true), scene.arrows())
                assertFalse("tab 0" in scene.shownTitles(), scene.shownTitles().toString())
            }
        }

    @Test
    fun `renders the title bar's tabs in five styles, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        val tabs = listOf(
            TabInformation(FakeTab("Leaf")),
            TabInformation(FakeTab("app", worktree = "fix-login")),
            TabInformation(FakeTab("Globex app", look = TabLook(name = "Globex app", color = 2))),
            TabInformation(FakeTab("dotfiles", look = TabLook(color = 5))),
            TabInformation(FakeTab(null, look = null)),
        )

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.SOLARIZED, BuiltInStyles.TEAL, BuiltInStyles.LEAF)) {
            for (theme in listOf(Theme.Light, Theme.Dark)) {
                TabsScene(tabs, barWidth = 1000, theme = theme, style = style, density = 2f).use { scene ->
                    scene.settle()
                    scene.writePng(File(folder, "title-bar-tabs-${style.id}-${theme.name.lowercase()}.png"))
                }
            }
        }
    }

    private class FakeTab(
        name: String?,
        worktree: String? = null,
        look: TabLook? = TabLook(),
    ) : TabInformationProvider {
        override val tabName = MutableStateFlow(name)
        override val worktreeName = MutableStateFlow(worktree)
        override val extraInfo = MutableStateFlow(name?.let { "/repos/$it" })
        override val tabLook = MutableStateFlow(look)
        override fun renameTab(typed: String) {}
        override fun resetTabName() {}
        override fun setTabColor(color: Int?) {}
        override fun dispose() {}
    }

    private class TabsScene(
        private val tabs: List<TabInformation<FakeTab>>,
        barWidth: Int,
        theme: Theme = Theme.Light,
        style: StyleDefinition = BuiltInStyles.TONAL,
        density: Float = 1f,
    ) : AutoCloseable {
        constructor(tabCount: Int, barWidth: Int) : this(List(tabCount) { TabInformation(FakeTab("tab $it")) }, barWidth)

        private val scene = ImageComposeScene(
            width = ((barWidth + 200) * density).toInt(),
            height = (40 * density).toInt(),
            density = Density(density),
            coroutineContext = Dispatchers.Swing,
        ) {
            AppTheme(theme, style = style) {
                Box(Modifier.background(MaterialTheme.frameColors.background).fillMaxSize()) {
                    Box(Modifier.width(barWidth.dp).height(40.dp), contentAlignment = Alignment.CenterStart) {
                        TabsRow(
                            tabs = tabs,
                            defaultTabName = "New tab",
                            currentTab = tabs.first(),
                            onTabSelected = {},
                            onTabClosed = {},
                            onMoveTab = { _, _ -> },
                            onAddNewTab = {},
                            titleBarWidth = barWidth.dp,
                        )
                    }
                }
            }
        }

        suspend fun settle() {
            repeat(8) {
                scene.render(System.nanoTime())
                delay(30)
            }
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

        private fun textOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

        /** The widths of the tabs that show at least in part, from their clickable boxes, as laid out (not clipped). */
        fun tabWidths(): List<Float> = nodes()
            .filter { node -> textOf(node)?.startsWith("tab ") == true }
            .mapNotNull { title -> generateSequence(title) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) } }
            .distinct()
            .filter { it.boundsInRoot.width > 0f }
            .map { it.size.width.toFloat() }

        fun shownTitles(): List<String> = nodes()
            .filter { node -> textOf(node)?.startsWith("tab ") == true && node.boundsInRoot.left >= 0f && node.boundsInRoot.width > 0f }
            .mapNotNull(::textOf)

        fun arrows(): List<Pair<String, Boolean>> = nodes()
            .mapNotNull { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                    ?: return@mapNotNull null
                if (label != "Earlier tabs" && label != "Later tabs") return@mapNotNull null
                val button = generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }

                label to !button.config.contains(SemanticsProperties.Disabled)
            }

        fun arrowRight(label: String): Float =
            nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == label }
                .boundsInRoot.right

        fun clickArrow(label: String) {
            val node = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == label }
            val button = generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }

            button.config[SemanticsActions.OnClick].action?.invoke()
        }

        fun writePng(file: File) {
            file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }

        override fun close() = scene.close()
    }
}
