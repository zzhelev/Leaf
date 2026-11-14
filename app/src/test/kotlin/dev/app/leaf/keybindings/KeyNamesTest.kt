// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.keybindings

import dev.app.leaf.common.OS
import dev.app.leaf.ui.getParts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The keys that tooltips, the toolbar and the Actions list name (fork-only). `getParts` throws for a key it has no name
 * for, which in a composition takes the view away without a word: find's strip never showed on Linux and Windows, where
 * its tooltips name F3. Each system's keys are checked here, whatever system runs the test.
 */
class KeyNamesTest {
    /**
     * The options whose first key the UI names (`keyBinding`): the toolbar, the panes' toggles, the Actions list, the
     * log's search and find's strip.
     */
    private val namedOptions = listOf(
        KeybindingOption.BRANCH_CREATE,
        KeybindingOption.SETTINGS,
        KeybindingOption.PULL,
        KeybindingOption.PUSH,
        KeybindingOption.STASH,
        KeybindingOption.STASH_POP,
        KeybindingOption.QUICK_ACTIONS,
        KeybindingOption.BRANCHES_PANE,
        KeybindingOption.CHANGES_PANE,
        KeybindingOption.FIND,
        KeybindingOption.FIND_NEXT,
        KeybindingOption.FIND_PREVIOUS,
    )

    @Test
    fun `every key that the UI names has a name on every system`() {
        for (os in OS.entries) {
            for (option in namedOptions) {
                val keybinding = keybindingsOf(os)[option]?.firstOrNull() ?: continue

                runCatching { getParts(keybinding) }.onFailure { error ->
                    throw AssertionError("$option on $os: $keybinding has no name", error)
                }
            }
        }
    }

    @Test
    fun `find's next and previous match are named F3 and Shift+F3 on Linux and Windows`() {
        for (os in listOf(OS.LINUX, OS.WINDOWS)) {
            val keys = keybindingsOf(os)

            assertEquals("F3", getParts(keys.getValue(KeybindingOption.FIND_NEXT).first()).joinToString("+"))
            assertEquals("Shift+F3", getParts(keys.getValue(KeybindingOption.FIND_PREVIOUS).first()).joinToString("+"))
        }
    }
}
