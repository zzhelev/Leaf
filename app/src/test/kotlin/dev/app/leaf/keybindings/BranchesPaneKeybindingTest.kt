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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shortcut that folds the branches pane away and shows it again (fork-only): Ctrl+Shift+B, Cmd+Shift+B on a Mac. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class BranchesPaneKeybindingTest {
    private val isMac = currentOs == OS.MAC

    /** The command key of this system: Cmd on a Mac, Ctrl elsewhere. */
    private fun press(
        key: Key,
        shift: Boolean = false,
        command: Boolean = true,
        alt: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown,
    ) = KeyEvent(
        key,
        type,
        isShiftPressed = shift,
        isAltPressed = alt,
        isCtrlPressed = command && !isMac,
        isMetaPressed = command && isMac,
    )

    @Test
    fun `the command key with Shift and B folds the pane`() {
        assertTrue(press(Key.B, shift = true).matchesBinding(KeybindingOption.BRANCHES_PANE))
    }

    @Test
    fun `without Shift it is New branch, and with Shift it is not`() {
        assertTrue(press(Key.B).matchesBinding(KeybindingOption.BRANCH_CREATE))
        assertFalse(press(Key.B).matchesBinding(KeybindingOption.BRANCHES_PANE))
        assertFalse(press(Key.B, shift = true).matchesBinding(KeybindingOption.BRANCH_CREATE))
    }

    @Test
    fun `other keys and modifiers do not match`() {
        assertFalse(press(Key.B, shift = true, command = false).matchesBinding(KeybindingOption.BRANCHES_PANE))
        assertFalse(press(Key.B, shift = true, alt = true).matchesBinding(KeybindingOption.BRANCHES_PANE))
        assertFalse(press(Key.N, shift = true).matchesBinding(KeybindingOption.BRANCHES_PANE))
    }

    @Test
    fun `it fires on the key going down only`() {
        assertFalse(press(Key.B, shift = true, type = KeyEventType.KeyUp).matchesBinding(KeybindingOption.BRANCHES_PANE))
    }

    @Test
    fun `it is the only option on that key`() {
        val binding = KeybindingOption.BRANCHES_PANE.keyBinding

        assertNotNull(binding)
        assertEquals(Key.B, binding!!.key)
        assertTrue(binding.shift)
        assertEquals(isMac, binding.meta)
        assertEquals(!isMac, binding.control)

        val others = keybindings.filterKeys { it != KeybindingOption.BRANCHES_PANE }
            .filterValues { list -> binding in list }

        assertEquals(emptyMap<KeybindingOption, List<Keybinding>>(), others)
    }
}
