// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.dropdowns.DropDownOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ROW_WIDTH = 560
private val LONG_SUBTITLE = "A subtitle long enough to wrap. ".repeat(8)

/** A settings row keeps its control on screen whatever the length of its subtitle (fork-only). */
class SettingsRowsTest {
    private val toggle: @Composable (String) -> Unit = { subtitle ->
        SettingToggle(title = "Toggle", subtitle = subtitle, value = true, onValueChanged = {})
    }

    private val dropDown: @Composable (String) -> Unit = { subtitle ->
        SettingDropDown(
            title = "Drop-down",
            subtitle = subtitle,
            dropDownOptions = listOf(DropDownOption(10, "Every 10 s")),
            onOptionSelected = {},
            currentOption = 10,
        )
    }

    @Test
    fun `a long subtitle wraps and leaves the control its room`() {
        for ((name, row) in listOf("switch" to toggle, "drop-down" to dropDown)) {
            val rendered = render { row(LONG_SUBTITLE) }
            val control = rendered.control
            val subtitle = rendered.texts.getValue(LONG_SUBTITLE)

            assertTrue(control.width > 0f, "the $name has no width")
            assertTrue(control.right <= ROW_WIDTH, "the $name ends at ${control.right}")
            assertTrue(control.left >= subtitle.right, "the $name starts at ${control.left}, over the subtitle")
            assertTrue(subtitle.height > control.height, "the subtitle next to the $name didn't wrap")
        }
    }

    @Test
    fun `a short subtitle still puts the control at the end of the row`() {
        for ((name, row) in listOf("switch" to toggle, "drop-down" to dropDown)) {
            assertEquals(ROW_WIDTH.toFloat(), render { row("Short") }.control.right, 1f, name)
        }
    }

    private class Rendered(val control: Rect, val texts: Map<String, Rect>)

    /** Renders one row, [ROW_WIDTH] wide, and gives the bounds of its clickable control and of each text. */
    private fun render(row: @Composable () -> Unit): Rendered {
        val scene = ImageComposeScene(width = 700, height = 400, density = Density(1f)) {
            AppTheme {
                Box(Modifier.width(ROW_WIDTH.dp)) { row() }
            }
        }

        try {
            scene.render()

            var control: Rect? = null
            val texts = mutableMapOf<String, Rect>()

            fun visit(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts[it.text] = node.boundsInRoot }

                if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
                    control = node.boundsInRoot
                }

                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }

            return Rendered(checkNotNull(control) { "nothing clickable" }, texts)
        } finally {
            scene.close()
        }
    }
}
