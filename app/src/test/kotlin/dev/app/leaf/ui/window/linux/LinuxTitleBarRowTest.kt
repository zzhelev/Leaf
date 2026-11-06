// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

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
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.dialogs.settings.AppearanceRows
import dev.app.leaf.ui.window.linux.LinuxFrameSupport.Reason
import dev.app.leaf.viewmodels.emptySettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Settings → Appearance → "Tabs in the title bar", on Linux (docs/fork/window-chrome.md, section 10), rendered. */
class LinuxTitleBarRowTest {
    private var scene: ImageComposeScene? = null
    private val changes = mutableListOf<Boolean>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(isOn: Boolean, support: LinuxFrameSupport, isDrawnNow: Boolean) {
        scene?.close()
        scene = ImageComposeScene(width = 900, height = 200, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.width(860.dp)) {
                    Column { LinuxTitleBarRow(isOn, support, isDrawnNow) { changes += it } }
                }
            }
        }.also { it.render() }
    }

    private val description =
        "Leaf draws the window's frame, with the tabs in its title bar and its own window buttons, as on macOS and Windows."

    @Test
    fun `where the desktop allows it, the row says what it does, and to restart while the window differs`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(isOn = false, LinuxFrameSupport.Supported, isDrawnNow = false)
            assertTrue("Tabs in the title bar" in texts())
            assertTrue(description in texts(), texts().toString())
            assertFalse(isDisabled())

            show(isOn = true, LinuxFrameSupport.Supported, isDrawnNow = false)
            assertTrue("$description Restart Leaf to apply it." in texts(), texts().toString())

            show(isOn = true, LinuxFrameSupport.Supported, isDrawnNow = true)
            assertTrue(description in texts())

            show(isOn = false, LinuxFrameSupport.Supported, isDrawnNow = true)
            assertTrue("$description Restart Leaf to apply it." in texts())
        }

    @Test
    fun `turning it on sets the setting`(): Unit = runBlocking(Dispatchers.Swing) {
        show(isOn = false, LinuxFrameSupport.Supported, isDrawnNow = false)

        // The switch is the row's one control
        val switch = nodes().first { SemanticsActions.OnClick in it.config }
        switch.config[SemanticsActions.OnClick].action?.invoke()

        assertEquals(listOf(true), changes)
    }

    @Test
    fun `where the desktop doesn't allow it, the row is greyed out and says why`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val reasons = mapOf(
                Reason.NOT_JETBRAINS_RUNTIME to "Not available on this Java, which can't move a window for Leaf. The .deb package's runtime can.",
                Reason.OTHER_TOOLKIT to "Not available: Leaf's window system is neither X11 nor Wayland.",
                Reason.WSL to "Not available under WSL.",
                Reason.TILING_WINDOW_MANAGER to "Not available with a tiling window manager, which places windows itself.",
                Reason.UNKNOWN_DESKTOP to "Not available on a desktop that Leaf doesn't know.",
            )

            for ((reason, text) in reasons) {
                show(isOn = false, LinuxFrameSupport.Unsupported(reason), isDrawnNow = false)

                assertTrue(text in texts(), "$reason: ${texts()}")
                assertTrue(isDisabled(), "$reason")
            }
        }

    @Test
    fun `Settings → Appearance has the row on Linux only`(): Unit = runBlocking(Dispatchers.Swing) {
        scene = ImageComposeScene(width = 900, height = 1600, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.width(860.dp)) {
                    Column { AppearanceRows(emptySettingsState()) {} }
                }
            }
        }.also { it.render() }

        assertEquals(currentOs == OS.LINUX, "Tabs in the title bar" in texts())
    }

    /** A switch that is off takes no click. */
    private fun isDisabled() = nodes().none { SemanticsActions.OnClick in it.config }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
}
