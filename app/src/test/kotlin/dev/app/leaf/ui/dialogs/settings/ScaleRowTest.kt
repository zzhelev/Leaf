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
import dev.app.leaf.domain.models.ui.UiScale
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

/** Settings → Appearance's Scale row (fork-only: on top of the screen's own scale), rendered offscreen and picked from. */
class ScaleRowTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<SettingsAction>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(stored: Float) {
        scene?.close()
        scene = ImageComposeScene(width = 900, height = 1600, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.width(860.dp)) {
                    Column { AppearanceRows(emptySettingsState().copy(scaleUi = stored)) { actions += it } }
                }
            }
        }.also { it.render() }
    }

    @Test
    fun `the choices are the scales, the default one marked`() {
        val options = scaleOptions(UiScale.DEFAULT)

        assertEquals(UiScale.CHOICES, options.map { it.value })
        assertEquals(
            listOf("75%", "100% (default)", "125%", "150%", "175%", "200%", "225%", "250%", "275%", "300%"),
            options.map { it.optionName },
        )
    }

    @Test
    fun `a stored scale that the list lacks goes in its place, once`() {
        // As an old scale turns into: 125% on a Windows screen at 150%, 100% on a Retina screen
        val options = scaleOptions(0.83f)
        assertEquals(listOf("75%", "83%", "100% (default)", "125%"), options.take(4).map { it.optionName })

        assertEquals("50%", scaleOptions(0.5f).first().optionName)
        assertEquals(UiScale.CHOICES.size, scaleOptions(1.5f).size)
    }

    @Test
    fun `the row shows the stored scale`(): Unit = runBlocking(Dispatchers.Swing) {
        show(UiScale.DEFAULT)
        assertTrue("100% (default)" in texts(), texts().toString())
        assertTrue("The size of everything: text, icons and spacing" in texts(), texts().toString())

        show(1.5f)
        assertTrue("150%" in texts(), texts().toString())

        show(0.83f)
        assertTrue("83%" in texts(), texts().toString())
    }

    @Test
    fun `picking a scale stores it`(): Unit = runBlocking(Dispatchers.Swing) {
        show(UiScale.DEFAULT)
        open("100% (default)")
        pick("150%")

        show(0.83f)
        open("83%")
        pick("100% (default)")

        assertEquals(
            listOf<SettingsAction>(
                SettingsAction.SetConfig(AppConfig.ScaleUi(1.5f)),
                SettingsAction.SetConfig(AppConfig.ScaleUi(1f)),
            ),
            actions,
        )
    }

    private fun open(current: String) = click(nodes().first { current in it.texts() })

    /** The menu's item, which comes after the row's own text when it has the same name. */
    private fun pick(name: String) {
        val item = nodes().last { name in it.texts() }
        val clickable = generateSequence(item) { it.parent }.first { SemanticsActions.OnClick in it.config }
        clickable.config[SemanticsActions.OnClick].action?.invoke()
    }

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
