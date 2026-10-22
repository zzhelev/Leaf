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
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.SettingsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Settings → Appearance → Style (fork-only): its choices. */
class StyleSettingRowTest {
    private val teal = BuiltInStyles.QUIET.copy(id = "teal", name = "Teal")
    private val styles = BuiltInStyles.ALL + teal

    private inner class Row(style: StyleDefinition) : AutoCloseable {
        val actions = mutableListOf<SettingsAction>()

        private val scene = ImageComposeScene(width = 700, height = 400, density = Density(1f)) {
            AppTheme {
                Box(Modifier.width(560.dp)) {
                    StyleSetting(style, styles, onAction = { actions += it })
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
    fun `the row is called Style, shows the style chosen and offers every style in order`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Row(BuiltInStyles.TONAL).use { row ->
                assertTrue("Style" in row.texts(), row.texts().toString())
                assertTrue(row.texts().any { it.startsWith("The colours of buttons, notices, backgrounds and text") })

                row.click("Tonal")

                val choices = row.texts().filter { it in setOf("Tonal", "Classic", "Quiet", "Teal") }
                assertEquals(listOf("Tonal", "Tonal", "Classic", "Quiet", "Teal"), choices, "The row's value, then the menu")

                row.click("Teal")

                assertEquals(listOf<SettingsAction>(SettingsAction.SetConfig(AppConfig.Style("teal"))), row.actions)
            }
        }

    @Test
    fun `each style shows by its name`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((style, name) in listOf(
            BuiltInStyles.TONAL to "Tonal",
            BuiltInStyles.CLASSIC to "Classic",
            BuiltInStyles.QUIET to "Quiet",
            teal to "Teal",
        )) {
            Row(style).use { row ->
                assertEquals(1, row.clickables(name).size, style.id)
            }
        }
    }
}
