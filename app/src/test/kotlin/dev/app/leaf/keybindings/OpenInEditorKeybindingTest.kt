// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.keybindings

import dev.app.leaf.common.OS
import dev.app.leaf.ui.getParts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The shortcut that opens the shown diff's file in the editor (fork-only): Ctrl+E, ⌘E on a Mac. */
class OpenInEditorKeybindingTest {
    @Test
    fun `it is the command key with E on each system`() {
        assertEquals("⌘+E", getParts(keybindingsOf(OS.MAC).getValue(KeybindingOption.OPEN_IN_EDITOR).single()).joinToString("+"))
        for (os in listOf(OS.LINUX, OS.WINDOWS)) {
            assertEquals("Ctrl+E", getParts(keybindingsOf(os).getValue(KeybindingOption.OPEN_IN_EDITOR).single()).joinToString("+"))
        }
    }

    @Test
    fun `no other option has it`() {
        for (os in listOf(OS.MAC, OS.LINUX, OS.WINDOWS)) {
            val keys = keybindingsOf(os)
            val binding = keys.getValue(KeybindingOption.OPEN_IN_EDITOR).single()
            val others = keys.filterKeys { it != KeybindingOption.OPEN_IN_EDITOR }.filterValues { binding in it }

            assertEquals(emptyMap<KeybindingOption, List<Keybinding>>(), others, os.name)
        }
    }
}
