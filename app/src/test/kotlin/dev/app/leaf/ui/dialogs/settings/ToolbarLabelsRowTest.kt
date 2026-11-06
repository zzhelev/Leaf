// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.SettingsAction
import dev.app.leaf.viewmodels.emptySettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Settings → Appearance's Toolbar labels row (fork-only), rendered offscreen and switched. */
class ToolbarLabelsRowTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<SettingsAction>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(toolbarLabels: Boolean) {
        scene?.close()
        scene = ImageComposeScene(width = 900, height = 1600, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.width(860.dp)) {
                    Column { AppearanceRows(emptySettingsState().copy(toolbarLabels = toolbarLabels)) { actions += it } }
                }
            }
        }.also { it.render() }
    }

    @Test
    fun `the row comes right after Text size, and says what it does`(): Unit = runBlocking(Dispatchers.Swing) {
        show(toolbarLabels = true)

        val title = boundsOf("Toolbar labels").top
        assertTrue(boundsOf("Text size").top < title, "Text size comes first")
        assertTrue(title < boundsOf("Avatar provider").top, "the avatar provider comes after")
        assertTrue(
            "Show the names of the toolbar's buttons next to their icons. They still hide in a narrow window." in texts()
        )
    }

    @Test
    fun `switching it sets the other value`(): Unit = runBlocking(Dispatchers.Swing) {
        for (isOn in listOf(true, false)) {
            show(toolbarLabels = isOn)

            actions.clear()
            checkNotNull(switchOf("Toolbar labels").config[SemanticsActions.OnClick].action).invoke()

            assertEquals(listOf<SettingsAction>(SettingsAction.SetConfig(AppConfig.ToolbarLabels(!isOn))), actions)
        }
    }

    /** The switch on [title]'s row: what can be clicked right of it, nearest the title and its subtitle. */
    private fun switchOf(title: String): SemanticsNode {
        val titleBounds = boundsOf(title)

        return nodes()
            .filter { SemanticsActions.OnClick in it.config && it.boundsInRoot.left > titleBounds.right }
            .minBy { kotlin.math.abs(it.boundsInRoot.center.y - titleBounds.bottom) }
    }

    private fun boundsOf(text: String) = nodes().first { text in it.texts() }.boundsInRoot

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
