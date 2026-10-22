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

/**
 * The shortcut that folds the changes pane away and shows it again (fork-only): Ctrl+Shift+C, Cmd+Shift+C on a Mac. It
 * is the branches pane's Ctrl+Shift+B with the pane's initial, so that the two are learned together.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ChangesPaneKeybindingTest {
    private val isMac = currentOs == OS.MAC

    /** The command key of this system: Cmd on a Mac, Ctrl elsewhere. */
    private fun press(
        key: Key,
        shift: Boolean = true,
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
    fun `the command key with Shift and C folds the pane`() {
        assertTrue(press(Key.C).matchesBinding(KeybindingOption.CHANGES_PANE))
    }

    @Test
    fun `it is the branches pane's shortcut with C in place of B`() {
        val branches = checkNotNull(KeybindingOption.BRANCHES_PANE.keyBinding)
        val changes = checkNotNull(KeybindingOption.CHANGES_PANE.keyBinding)

        assertEquals(Key.B, branches.key)
        assertEquals(Key.C, changes.key)
        assertEquals(branches.copy(key = Key.C), changes)
    }

    @Test
    fun `each shortcut folds its own pane only`() {
        assertFalse(press(Key.C).matchesBinding(KeybindingOption.BRANCHES_PANE))
        assertFalse(press(Key.B).matchesBinding(KeybindingOption.CHANGES_PANE))
    }

    @Test
    fun `without Shift it does not match`() {
        assertFalse(press(Key.C, shift = false).matchesBinding(KeybindingOption.CHANGES_PANE))
    }

    @Test
    fun `other keys and modifiers do not match`() {
        assertFalse(press(Key.C, command = false).matchesBinding(KeybindingOption.CHANGES_PANE))
        assertFalse(press(Key.C, alt = true).matchesBinding(KeybindingOption.CHANGES_PANE))
        assertFalse(press(Key.V).matchesBinding(KeybindingOption.CHANGES_PANE))
    }

    @Test
    fun `it fires on the key going down only`() {
        assertFalse(press(Key.C, type = KeyEventType.KeyUp).matchesBinding(KeybindingOption.CHANGES_PANE))
    }

    @Test
    fun `it is the only option on that key`() {
        val binding = KeybindingOption.CHANGES_PANE.keyBinding

        assertNotNull(binding)
        assertTrue(binding!!.shift)
        assertEquals(isMac, binding.meta)
        assertEquals(!isMac, binding.control)

        val others = keybindings.filterKeys { it != KeybindingOption.CHANGES_PANE }
            .filterValues { list -> binding in list }

        assertEquals(emptyMap<KeybindingOption, List<Keybinding>>(), others)
    }
}
