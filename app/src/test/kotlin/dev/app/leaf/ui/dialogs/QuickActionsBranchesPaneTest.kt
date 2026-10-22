// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.code
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.getParts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Actions list's entry that folds the branches pane away or shows it (fork-only). */
@OptIn(ExperimentalComposeUiApi::class)
class QuickActionsBranchesPaneTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<QuickActionType>()

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `the entry says what it does now`() {
        assertEquals("Hide branches pane", branchesPaneAction(isFolded = false, hideTitle = "Hide branches pane", showTitle = "Show branches pane").title)
        assertEquals("Show branches pane", branchesPaneAction(isFolded = true, hideTitle = "Hide branches pane", showTitle = "Show branches pane").title)
    }

    @Test
    fun `it toggles, and carries the shortcut that does the same`() {
        val action = branchesPaneAction(isFolded = false, hideTitle = "Hide", showTitle = "Show")

        assertEquals(QuickActionType.TOGGLE_BRANCHES_PANE, action.type)
        assertNotNull(action.keybinding)
        assertEquals(KeybindingOption.BRANCHES_PANE.keyBinding, action.keybinding)
    }

    @Test
    fun `an entry without a shortcut has none`() {
        assertNull(QuickAction(Res.drawable.code, "Open repository in file manager", QuickActionType.OPEN_DIR_IN_FILE_MANAGER).keybinding)
    }

    private fun render(vararg items: QuickAction) {
        scene = ImageComposeScene(width = 900, height = 600, density = Density(1f)) {
            AppTheme {
                QuickActionView(
                    onDismiss = {},
                    selectedIndex = 0,
                    filteredItems = items.toList(),
                    onAction = { actions += it },
                    searchFilter = "",
                    textFieldFocusRequester = FocusRequester(),
                    onChangeSelectedIndex = {},
                    onChangeSearchFilter = {},
                )
            }
        }
        scene!!.render()
        scene!!.render()
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }

        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

        return nodes
    }

    private fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

    @Test
    fun `the list shows the shortcut at the end of the entry's row, and only there`() {
        val hint = getParts(checkNotNull(KeybindingOption.BRANCHES_PANE.keyBinding)).joinToString("+")

        render(
            QuickAction(Res.drawable.code, "Open repository in file manager", QuickActionType.OPEN_DIR_IN_FILE_MANAGER),
            branchesPaneAction(isFolded = false, hideTitle = "Hide branches pane", showTitle = "Show branches pane"),
        )

        assertTrue("Hide branches pane" in texts())
        assertEquals(1, texts().count { it == hint }, texts().toString())
        assertFalse(texts().any { it.contains("Ctrl") && it != hint }, texts().toString())
    }

    @Test
    fun `a click on the entry runs it`() {
        render(
            QuickAction(Res.drawable.code, "Open repository in file manager", QuickActionType.OPEN_DIR_IN_FILE_MANAGER),
            branchesPaneAction(isFolded = true, hideTitle = "Hide branches pane", showTitle = "Show branches pane"),
        )

        val row = nodes().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Show branches pane" }
        }

        checkNotNull(row.config[SemanticsActions.OnClick].action).invoke()

        assertEquals(listOf(QuickActionType.TOGGLE_BRANCHES_PANE), actions)
    }
}
