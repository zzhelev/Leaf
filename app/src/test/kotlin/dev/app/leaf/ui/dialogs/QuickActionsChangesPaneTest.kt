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
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.getParts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** The Actions list's entry that folds the changes pane away or shows it (fork-only). */
@OptIn(ExperimentalComposeUiApi::class)
class QuickActionsChangesPaneTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<QuickActionType>()

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `the entry says what it does now`() {
        assertEquals("Hide changes pane", changesPaneAction(isFolded = false, hideTitle = "Hide changes pane", showTitle = "Show changes pane").title)
        assertEquals("Show changes pane", changesPaneAction(isFolded = true, hideTitle = "Hide changes pane", showTitle = "Show changes pane").title)
    }

    @Test
    fun `it toggles the changes pane, and carries the shortcut that does the same`() {
        val action = changesPaneAction(isFolded = false, hideTitle = "Hide", showTitle = "Show")

        assertEquals(QuickActionType.TOGGLE_CHANGES_PANE, action.type)
        assertNotNull(action.keybinding)
        assertEquals(KeybindingOption.CHANGES_PANE.keyBinding, action.keybinding)
    }

    @Test
    fun `it is not the branches pane's entry`() {
        val changes = changesPaneAction(isFolded = false, hideTitle = "Hide", showTitle = "Show")
        val branches = branchesPaneAction(isFolded = false, hideTitle = "Hide", showTitle = "Show")

        assertNotEquals(branches.type, changes.type)
        assertNotEquals(branches.keybinding, changes.keybinding)
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
    fun `the list shows the shortcut at the end of the entry's row, and the branches pane's at its own`() {
        val changesHint = getParts(checkNotNull(KeybindingOption.CHANGES_PANE.keyBinding)).joinToString("+")
        val branchesHint = getParts(checkNotNull(KeybindingOption.BRANCHES_PANE.keyBinding)).joinToString("+")

        render(
            branchesPaneAction(isFolded = false, hideTitle = "Hide branches pane", showTitle = "Show branches pane"),
            changesPaneAction(isFolded = false, hideTitle = "Hide changes pane", showTitle = "Show changes pane"),
        )

        assertEquals(1, texts().count { it == changesHint }, texts().toString())
        assertEquals(1, texts().count { it == branchesHint }, texts().toString())
        assertNotEquals(changesHint, branchesHint)
    }

    @Test
    fun `a click on the entry runs it`() {
        render(changesPaneAction(isFolded = true, hideTitle = "Hide changes pane", showTitle = "Show changes pane"))

        val row = nodes().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Show changes pane" }
        }

        checkNotNull(row.config[SemanticsActions.OnClick].action).invoke()

        assertEquals(listOf(QuickActionType.TOGGLE_CHANGES_PANE), actions)
    }
}
