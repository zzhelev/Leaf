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
import dev.app.leaf.ui.TextSizeStep
import dev.app.leaf.ui.getParts
import dev.app.leaf.ui.textSizeStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The text size's shortcuts (fork-only): Cmd on a Mac, Ctrl elsewhere, with `+`, `−` and `0`. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TextSizeKeybindingTest {
    private val isMac = currentOs == OS.MAC

    /** The command key of this system: Cmd on a Mac, Ctrl elsewhere. */
    private fun press(
        key: Key,
        shift: Boolean = false,
        command: Boolean = true,
        otherCommand: Boolean = false,
        alt: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown,
    ) = KeyEvent(
        key,
        type,
        isShiftPressed = shift,
        isAltPressed = alt,
        isCtrlPressed = (command && !isMac) || (otherCommand && isMac),
        isMetaPressed = (command && isMac) || (otherCommand && !isMac),
    )

    @Test
    fun `plus, as the equals key with or without Shift, its own key or the number pad's, makes the text bigger`() {
        for (event in listOf(
            press(Key.Equals),
            press(Key.Equals, shift = true),
            press(Key.Plus),
            press(Key.Plus, shift = true),
            press(Key.NumPadAdd),
        )) {
            assertEquals(TextSizeStep.BIGGER, event.textSizeStep(), "$event")
        }
    }

    @Test
    fun `minus makes it smaller and zero goes back to the default, from either row of keys`() {
        assertEquals(TextSizeStep.SMALLER, press(Key.Minus).textSizeStep())
        assertEquals(TextSizeStep.SMALLER, press(Key.NumPadSubtract).textSizeStep())
        assertEquals(TextSizeStep.DEFAULT, press(Key.Zero).textSizeStep())
        assertEquals(TextSizeStep.DEFAULT, press(Key.NumPad0).textSizeStep())
    }

    @Test
    fun `without the command key, with another one, or on the key going up, nothing happens`() {
        for (event in listOf(
            press(Key.Equals, command = false),
            press(Key.Minus, command = false),
            press(Key.Zero, command = false),
            press(Key.Equals, command = false, otherCommand = true),
            press(Key.Minus, alt = true),
            press(Key.Minus, shift = true),
            press(Key.Zero, shift = true),
            press(Key.Equals, type = KeyEventType.KeyUp),
            press(Key.One),
        )) {
            assertNull(event.textSizeStep(), "$event")
        }
    }

    @Test
    fun `no other option shares these keys`() {
        val options = listOf(
            KeybindingOption.TEXT_BIGGER,
            KeybindingOption.TEXT_SMALLER,
            KeybindingOption.TEXT_DEFAULT_SIZE,
        )
        val ours = options.flatMap { keybindings.getValue(it) }
        val others = keybindings.filterKeys { it !in options }.filterValues { list -> list.any { it in ours } }

        assertEquals(emptyMap<KeybindingOption, List<Keybinding>>(), others)
    }

    @Test
    fun `their keys have names, so that a hint can show them`() {
        val bigger = KeybindingOption.TEXT_BIGGER.keyBinding!!

        assertEquals(if (isMac) listOf("⌘", "=") else listOf("Ctrl", "="), getParts(bigger))
        assertEquals("−", getParts(KeybindingOption.TEXT_SMALLER.keyBinding!!).last())
        assertEquals("0", getParts(KeybindingOption.TEXT_DEFAULT_SIZE.keyBinding!!).last())
    }
}
