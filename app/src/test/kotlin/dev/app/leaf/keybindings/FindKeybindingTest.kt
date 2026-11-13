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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The keys of find (fork-only, upstream issues 275 and 312): Ctrl+F (⌘F), the next and the previous match, go to line,
 * Select all and Copy, as each system's editors have them.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FindKeybindingTest {
    private val isMac = currentOs == OS.MAC

    private fun key(
        key: Key,
        ctrl: Boolean = false,
        meta: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown,
    ) = KeyEvent(key, type, isCtrlPressed = ctrl, isMetaPressed = meta, isShiftPressed = shift, isAltPressed = alt)

    /** The command key of this system: Cmd on a Mac, Ctrl elsewhere. */
    private fun command(key: Key, shift: Boolean = false) = key(key, ctrl = !isMac, meta = isMac, shift = shift)

    private val findOptions = listOf(
        KeybindingOption.FIND,
        KeybindingOption.FIND_NEXT,
        KeybindingOption.FIND_PREVIOUS,
        KeybindingOption.GO_TO_LINE,
        KeybindingOption.SELECT_ALL,
        KeybindingOption.COPY,
    )

    private fun KeyEvent.options() = KeybindingOption.entries.filter { matchesBinding(it) }

    @Test
    fun `the command key and F finds, and nothing else`() {
        assertEquals(listOf(KeybindingOption.FIND), command(Key.F).options())
        assertFalse(key(Key.F, type = KeyEventType.KeyUp, ctrl = !isMac, meta = isMac).matchesBinding(KeybindingOption.FIND))
    }

    @Test
    fun `F3 and Shift+F3 step through the matches everywhere, and Cmd+G and Shift+Cmd+G on a Mac`() {
        assertEquals(listOf(KeybindingOption.FIND_NEXT), key(Key.F3).options())
        assertEquals(listOf(KeybindingOption.FIND_PREVIOUS), key(Key.F3, shift = true).options())

        assertEquals(isMac, key(Key.G, meta = true).matchesBinding(KeybindingOption.FIND_NEXT))
        assertEquals(isMac, key(Key.G, meta = true, shift = true).matchesBinding(KeybindingOption.FIND_PREVIOUS))
    }

    @Test
    fun `Ctrl+G goes to a line everywhere, and Cmd+L nowhere, as macOS can take it`() {
        assertEquals(listOf(KeybindingOption.GO_TO_LINE), key(Key.G, ctrl = true).options())
        assertFalse(key(Key.L, meta = true).matchesBinding(KeybindingOption.GO_TO_LINE))
        assertFalse(key(Key.G, ctrl = true, shift = true).matchesBinding(KeybindingOption.GO_TO_LINE))
    }

    @Test
    fun `the command key with A and C selects all and copies, and with Shift and C it folds the changes pane`() {
        assertEquals(listOf(KeybindingOption.SELECT_ALL), command(Key.A).options())
        assertEquals(listOf(KeybindingOption.COPY), command(Key.C).options())
        assertEquals(listOf(KeybindingOption.CHANGES_PANE), command(Key.C, shift = true).options())
        assertEquals(!isMac, key(Key.Insert, ctrl = true).matchesBinding(KeybindingOption.COPY))
    }

    @Test
    fun `no other option shares a key with find's`() {
        for (option in findOptions) {
            val bindings = keybindings[option].orEmpty()
            val shared = keybindings.filterKeys { it != option }.filterValues { list -> list.any { it in bindings } }

            assertEquals(emptyMap<KeybindingOption, List<Keybinding>>(), shared, option.name)
        }
    }

    @Test
    fun `the tooltips show this system's keys`() {
        val next = KeybindingOption.FIND_NEXT.keyBinding!!
        val goTo = KeybindingOption.GO_TO_LINE.keyBinding!!

        if (isMac) {
            assertEquals(Keybinding(key = Key.G, meta = true), next)
        } else {
            assertEquals(Keybinding(key = Key.F3), next)
        }
        assertEquals(Keybinding(key = Key.G, control = true), goTo)
    }
}
