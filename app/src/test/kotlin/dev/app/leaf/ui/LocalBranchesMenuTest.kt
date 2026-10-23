// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.RefPanelSettings
import dev.app.leaf.domain.sorting.RefSection
import dev.app.leaf.domain.sorting.RefSortState
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.components.sort.RefSortMenuButton
import dev.app.leaf.ui.context_menu.ContextMenuElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Delete merged branches…" is in the Local branches section's own ⋯ menu and on a right-click of its header, not in
 * the sort button's menu, which is about how branches are listed (fork-only, upstream issue 116).
 */
class LocalBranchesMenuTest {
    @Test
    fun `the section's menu has Delete merged branches`(): Unit = runBlocking(Dispatchers.Swing) {
        var deleted = 0

        Scene { LocalBranchesMenuButton(onDeleteMergedBranches = { deleted++ }) }.use { scene ->
            scene.clickTheButton()
            scene.click("Delete merged branches…")
        }

        assertEquals(1, deleted)
    }

    @Test
    fun `a right-click on the header offers it too`() {
        var deleted = 0
        val entry = localBranchesContextMenu(onDeleteMergedBranches = { deleted++ }).single()

        (entry as ContextMenuElement.ContextTextEntry).onClick()

        assertEquals(1, deleted)
    }

    @Test
    fun `the sort button's menu only sorts and groups`(): Unit = runBlocking(Dispatchers.Swing) {
        Scene {
            RefSortMenuButton(
                section = RefSection.Local,
                sortState = RefSortState(),
                settings = RefPanelSettings(),
                onSortChange = {},
                onKeepHeadOnTopToggle = {},
                onGroupByPrefixToggle = {},
            )
        }.use { scene ->
            scene.clickTheButton()

            assertTrue("Keep current branch on top" in scene.texts() && "Group by prefix" in scene.texts(), scene.texts().toString())
            assertFalse("Delete merged branches…" in scene.texts(), scene.texts().toString())
        }
    }

    private class Scene(content: @Composable () -> Unit) : AutoCloseable {
        val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            AppTheme(Theme.Dark) { content() }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** The menu button, the scene's only clickable before the menu opens. */
        fun clickTheButton() {
            checkNotNull(nodes().single { it.config.getOrNull(SemanticsActions.OnClick) != null }
                .config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        fun click(text: String) {
            val node = nodes().first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }
            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
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
