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
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.CodeColours
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

/** Settings → Appearance's Code colours row (fork-only), rendered offscreen and picked from. */
class CodeColoursRowTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<SettingsAction>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(codeColours: CodeColours) {
        scene?.close()
        scene = ImageComposeScene(width = 900, height = 1400, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.width(860.dp)) {
                    Column { AppearanceRows(emptySettingsState().copy(codeColours = codeColours)) { actions += it } }
                }
            }
        }.also { it.render() }
    }

    @Test
    fun `the row comes after the style gallery and before the lists' spacing, showing the choice`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(CodeColours.VS_CODE)

            val title = boundsOf("Code colours").top
            assertTrue(boundsOf("Import style…").top < title, "the gallery comes first")
            assertTrue(title < boundsOf("Lists spacing").top, "the lists' spacing comes after")
            val subtitle =
                "How code is coloured in diffs, blame and the conflict resolver. Leaf uses each style's own colours."
            assertTrue(subtitle in texts())
            // The drop-down shows the choice, on the row's line
            val choice = nodes().first { "VS Code" in it.texts() }.boundsInRoot
            assertTrue(choice.top < boundsOf("Lists spacing").top && choice.bottom > title, "$choice")

            show(CodeColours.LEAF)
            assertTrue("Leaf" in texts())
        }

    @Test
    fun `picking a choice sets it`(): Unit = runBlocking(Dispatchers.Swing) {
        show(CodeColours.LEAF)
        click(nodes().first { "Leaf" in it.texts() && it.boundsInRoot.top > boundsOf("Code colours").top - 40 })

        // The menu lists the three, in this order
        val items = listOf("Leaf", "VS Code", "IntelliJ").map { name -> nodes().last { name in it.texts() } }
        assertEquals(items.sortedBy { it.boundsInRoot.top }, items)

        val intellij = items.last()
        val clickable = generateSequence(intellij) { it.parent }.first { SemanticsActions.OnClick in it.config }
        clickable.config[SemanticsActions.OnClick].action?.invoke()
        val picked = SettingsAction.SetConfig(AppConfig.CodeColours(CodeColours.INTELLIJ))
        assertEquals(listOf<SettingsAction>(picked), actions)
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
