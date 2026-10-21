// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.SettingsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Settings → Appearance → Style (fork-only): its choices, and the row greyed out for a custom theme. */
class AccentStyleSettingRowTest {
    private class Row(style: AccentStyle, isCustomTheme: Boolean) : AutoCloseable {
        val actions = mutableListOf<SettingsAction>()

        private val scene = ImageComposeScene(width = 700, height = 400, density = Density(1f)) {
            AppTheme {
                Box(Modifier.width(560.dp)) {
                    AccentStyleSetting(style, isCustomTheme, onAction = { actions += it })
                }
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun clickables(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        fun click(text: String) {
            checkNotNull(clickables(text).single().config[SemanticsActions.OnClick].action).invoke()
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

    @Test
    fun `the row is called Style, shows the style chosen and offers the three in order`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Row(AccentStyle.TONAL, isCustomTheme = false).use { row ->
                assertTrue("Style" in row.texts(), row.texts().toString())
                assertTrue(row.texts().any { it.startsWith("How strong the colours of buttons and notices are.") })

                row.click("Tonal")

                val choices = row.texts().filter { it in setOf("Tonal", "Classic", "Quiet") }
                assertEquals(listOf("Tonal", "Tonal", "Classic", "Quiet"), choices, "The row's value, then the menu")

                row.click("Quiet")

                assertEquals(listOf<SettingsAction>(SettingsAction.SetConfig(AppConfig.AccentStyle(AccentStyle.QUIET))), row.actions)
            }
        }

    @Test
    fun `each style shows by its name`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((style, name) in listOf(AccentStyle.TONAL to "Tonal", AccentStyle.CLASSIC to "Classic", AccentStyle.QUIET to "Quiet")) {
            Row(style, isCustomTheme = false).use { row ->
                assertEquals(1, row.clickables(name).size, "$style")
            }
        }
    }

    @Test
    fun `with a custom theme, the row is greyed out, takes no click and says why`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Row(AccentStyle.CLASSIC, isCustomTheme = true).use { row ->
                assertTrue("Custom themes use their own colours." in row.texts(), row.texts().toString())
                assertTrue("Classic" in row.texts(), "It still shows the style chosen")
                assertEquals(emptyList<SemanticsNode>(), row.clickables("Classic"))
                assertEquals(emptyList<SettingsAction>(), row.actions)
            }
        }
}
