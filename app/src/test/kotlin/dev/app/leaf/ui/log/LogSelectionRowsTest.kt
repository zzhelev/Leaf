// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.repositoryopen.LogAction
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.selectedCommitsContextMenuItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val SEPARATOR = "---"

/**
 * The keys, the click and the menu of commits selected in the log with Shift (fork-only, step A2 of
 * `docs/fork/squash-and-rebase.md`).
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LogSelectionRowsTest {
    private val someone = Identity("Maya Chen", "maya.chen@example.com")
    private val commit = Commit("a".repeat(40), "Parse the config once", someone, someone, 0L, emptyList())

    private fun key(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        shift: Boolean = true,
        control: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ) = KeyEvent(key, type, isShiftPressed = shift, isCtrlPressed = control, isAltPressed = alt, isMetaPressed = meta)

    @Test
    fun `Shift and the arrows move a range's lead, and are no move of the selection`() {
        assertEquals(-1, key(Key.DirectionUp).logSelectionStep())
        assertEquals(1, key(Key.DirectionDown).logSelectionStep())
        assertNull(key(Key.DirectionUp).logMove())
        assertNull(key(Key.DirectionDown).logMove())
    }

    @Test
    fun `the arrows without Shift, with other keys, or released, aren't a range's`() {
        assertNull(key(Key.DirectionUp, shift = false).logSelectionStep())
        assertNull(key(Key.DirectionDown, control = true).logSelectionStep())
        assertNull(key(Key.DirectionDown, alt = true).logSelectionStep())
        assertNull(key(Key.DirectionDown, meta = true).logSelectionStep())
        assertNull(key(Key.DirectionDown, type = KeyEventType.KeyUp).logSelectionStep())
        assertNull(key(Key.PageDown).logSelectionStep())
        assertNull(key(Key.DirectionLeft).logSelectionStep())
    }

    @Test
    fun `Shift-click extends the range, also with the compare key held`() {
        assertEquals(
            LogAction.CommitRangeExtended(commit, isStash = false),
            commitRowClick(commit, isStash = false, isCompareClick = false, isRangeClick = true),
        )
        assertEquals(
            LogAction.CommitRangeExtended(commit, isStash = true),
            commitRowClick(commit, isStash = true, isCompareClick = true, isRangeClick = true),
        )
        assertEquals(LogAction.CommitSelected(commit, isStash = false), commitRowClick(commit, false, isCompareClick = false))
    }

    @Test
    fun `a selected row's menu copies the hashes and clears the selection`() {
        var copied = 0
        var cleared = 0
        val items = selectedCommitsContextMenuItems(4, onCopyHashes = { copied++ }, onClearSelection = { cleared++ })

        assertEquals(listOf("Copy 4 commit hashes", SEPARATOR, "Clear selection"), labelsOf(items))

        items.filterIsInstance<ContextMenuElement.ContextTextEntry>().forEach { it.onClick() }

        assertEquals(1, copied)
        assertEquals(1, cleared)
    }

    private fun labelsOf(items: List<ContextMenuElement>): List<String> = composed {
        items.map { item ->
            when (item) {
                is ContextMenuElement.ContextTextEntry -> item.composableLabel()
                else -> SEPARATOR
            }
        }
    }

    private fun <T> composed(read: @Composable () -> T): T {
        var value: T? = null
        val scene = ImageComposeScene(width = 10, height = 10) {
            value = read()
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
