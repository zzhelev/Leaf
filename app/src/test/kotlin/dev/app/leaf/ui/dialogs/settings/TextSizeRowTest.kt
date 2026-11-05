// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.TextSize
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

/** Settings → Appearance's Text size row (fork-only), rendered offscreen and picked from. */
class TextSizeRowTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<SettingsAction>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(textSize: Int) {
        scene?.close()
        scene = ImageComposeScene(width = 900, height = 1600, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.width(860.dp)) {
                    Column { AppearanceRows(emptySettingsState().copy(textSize = textSize)) { actions += it } }
                }
            }
        }.also { it.render() }
    }

    @Test
    fun `the options are the sizes, the default one marked`() {
        assertEquals(TextSize.CHOICES, textSizeOptions.map { it.value })
        assertEquals(
            listOf("11", "12", "13 (default)", "14", "15", "16", "17", "18", "20"),
            textSizeOptions.map { it.optionName },
        )
    }

    @Test
    fun `the row comes right after Scale, which says it sizes everything, and shows the size`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(16)

            val title = boundsOf("Text size").top
            assertTrue(boundsOf("Scale").top < title, "Scale comes first")
            assertTrue(title < boundsOf("Avatar provider").top, "the avatar provider comes after")
            assertTrue("The size of everything: text, icons and spacing" in texts())
            assertTrue("The size of the text only. ${textSizeShortcuts()}" in texts())

            val choice = nodes().first { "16" in it.texts() }.boundsInRoot
            assertTrue(choice.top < boundsOf("Avatar provider").top && choice.bottom > title, "$choice")

            show(TextSize.DEFAULT)
            assertTrue("13 (default)" in texts())
        }

    @Test
    fun `the subtitle names this system's shortcuts`() {
        val expected = if (currentOs == OS.MAC) "⌘+ and ⌘− change it" else "Ctrl with + or − changes it"

        assertTrue(textSizeShortcuts().startsWith(expected), textSizeShortcuts())
    }

    @Test
    fun `picking a size sets it`(): Unit = runBlocking(Dispatchers.Swing) {
        show(TextSize.DEFAULT)
        click(nodes().first { "13 (default)" in it.texts() })

        val items = listOf("12", "18", "20").map { name -> nodes().last { name in it.texts() } }
        assertEquals(items.sortedBy { it.boundsInRoot.top }, items)

        val clickable = generateSequence(items[1]) { it.parent }.first { SemanticsActions.OnClick in it.config }
        clickable.config[SemanticsActions.OnClick].action?.invoke()

        assertEquals(listOf<SettingsAction>(SettingsAction.SetConfig(AppConfig.TextSize(18))), actions)
    }

    private fun boundsOf(text: String) = nodes().first { text in it.texts() }.boundsInRoot

    private fun click(node: SemanticsNode) {
        val scene = scene!!
        val position = Offset(node.boundsInWindow.center.x, node.boundsInWindow.center.y)

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        scene.render()
        scene.render()
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
