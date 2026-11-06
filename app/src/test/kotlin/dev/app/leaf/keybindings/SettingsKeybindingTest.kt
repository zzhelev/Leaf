// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.keybindings

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.ui.getParts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shortcuts that open Settings: Ctrl+, (⌘, on a Mac), as in most apps, and upstream's Ctrl+Alt+S (⌘+Alt+S) too. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SettingsKeybindingTest {
    private val isMac = currentOs == OS.MAC

    private fun press(
        key: Key,
        meta: Boolean = false,
        control: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) = KeyEvent(
        key,
        KeyEventType.KeyDown,
        isShiftPressed = shift,
        isAltPressed = alt,
        isCtrlPressed = control,
        isMetaPressed = meta,
    )

    @Test
    fun `Ctrl and comma, or Cmd and comma on a Mac, opens Settings, and is the shortcut that tooltips show`() {
        assertTrue(press(Key.Comma, meta = isMac, control = !isMac).matchesBinding(KeybindingOption.SETTINGS))
        assertFalse(press(Key.Comma, meta = !isMac, control = isMac).matchesBinding(KeybindingOption.SETTINGS))
        assertFalse(press(Key.Comma, meta = isMac, control = !isMac, shift = true).matchesBinding(KeybindingOption.SETTINGS))
        assertFalse(press(Key.Comma).matchesBinding(KeybindingOption.SETTINGS))

        assertEquals(
            if (isMac) listOf("⌘", ",") else listOf("Ctrl", ","),
            getParts(KeybindingOption.SETTINGS.keyBinding!!),
        )
    }

    @Test
    fun `upstream's Ctrl+Alt+S stays, with the command key on a Mac`() {
        assertTrue(press(Key.S, meta = isMac, control = !isMac, alt = true).matchesBinding(KeybindingOption.SETTINGS))
    }

    @Test
    fun `no other option uses Settings' keys`() {
        val settings = keybindings.getValue(KeybindingOption.SETTINGS)
        val others = keybindings.filterKeys { it != KeybindingOption.SETTINGS }
            .filterValues { list -> list.any { it in settings } }

        assertEquals(emptyMap<KeybindingOption, List<Keybinding>>(), others)
    }
}
