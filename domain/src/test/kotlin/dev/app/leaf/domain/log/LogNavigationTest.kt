// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.models.ui.SelectedItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LogNavigationTest {
    @Test
    fun `up and down move one row`(): Unit {
        assertEquals(5, movedLogRow(4, 10, LogMove.DOWN, 8))
        assertEquals(3, movedLogRow(4, 10, LogMove.UP, 8))
    }

    @Test
    fun `the selection stops at the ends instead of wrapping`(): Unit {
        assertNull(movedLogRow(9, 10, LogMove.DOWN, 8))
        assertNull(movedLogRow(0, 10, LogMove.UP, 8))
        assertNull(movedLogRow(9, 10, LogMove.END, 8))
        assertNull(movedLogRow(0, 10, LogMove.HOME, 8))
    }

    @Test
    fun `a page moves by the page size and stops at the ends`(): Unit {
        assertEquals(12, movedLogRow(4, 30, LogMove.PAGE_DOWN, 8))
        assertEquals(0, movedLogRow(4, 30, LogMove.PAGE_UP, 8))
        assertEquals(29, movedLogRow(25, 30, LogMove.PAGE_DOWN, 8))
        assertEquals(1, movedLogRow(9, 30, LogMove.PAGE_UP, 8))
    }

    @Test
    fun `a page is at least one row`(): Unit {
        assertEquals(5, movedLogRow(4, 10, LogMove.PAGE_DOWN, 0))
        assertEquals(3, movedLogRow(4, 10, LogMove.PAGE_UP, -3))
    }

    @Test
    fun `home and end go to the first and last row`(): Unit {
        assertEquals(0, movedLogRow(7, 10, LogMove.HOME, 8))
        assertEquals(9, movedLogRow(7, 10, LogMove.END, 8))
    }

    @Test
    fun `without a selected row the first press selects the first row, and End the last`(): Unit {
        for (move in LogMove.entries.filter { it != LogMove.END }) {
            assertEquals(0, movedLogRow(null, 10, move, 8), "$move")
        }
        assertEquals(9, movedLogRow(null, 10, LogMove.END, 8))
    }

    @Test
    fun `a selected row outside the rows counts as none`(): Unit {
        assertEquals(0, movedLogRow(10, 10, LogMove.DOWN, 8))
        assertEquals(0, movedLogRow(-1, 10, LogMove.UP, 8))
        assertEquals(9, movedLogRow(50, 10, LogMove.END, 8))
    }

    @Test
    fun `no rows, no move`(): Unit {
        for (move in LogMove.entries) {
            assertNull(movedLogRow(null, 0, move, 8), "$move")
            assertNull(movedLogRow(0, 0, move, 8), "$move")
        }
    }

    @Test
    fun `one row stays selected`(): Unit {
        for (move in LogMove.entries) assertNull(movedLogRow(0, 1, move, 8), "$move")
        assertEquals(0, movedLogRow(null, 1, LogMove.END, 8))
    }

    private fun commit(hash: String) = Commit(
        hash = hash,
        message = "Fix $hash",
        committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        date = 0L,
        parentsHashes = emptyList(),
    )

    private fun item(hash: String) = SelectedItem.CommitItem(commit(hash), isStash = false, scrollToItem = false)

    private val hashes = listOf("a1", "b2", "c3")

    @Test
    fun `with an uncommitted changes row, it is row 0 and the commits follow`(): Unit {
        assertEquals(1, movedLogSelection(SelectedItem.UncommittedChanges, hashes, true, LogMove.DOWN, 8))
        assertEquals(0, movedLogSelection(item("a1"), hashes, true, LogMove.UP, 8))
        assertEquals(3, movedLogSelection(item("b2"), hashes, true, LogMove.DOWN, 8))
        assertNull(movedLogSelection(SelectedItem.UncommittedChanges, hashes, true, LogMove.UP, 8))
        assertNull(movedLogSelection(item("c3"), hashes, true, LogMove.DOWN, 8))
    }

    @Test
    fun `without an uncommitted changes row the first commit is row 0`(): Unit {
        assertNull(movedLogSelection(item("a1"), hashes, false, LogMove.UP, 8))
        assertEquals(1, movedLogSelection(item("a1"), hashes, false, LogMove.DOWN, 8))
        assertEquals(2, movedLogSelection(item("a1"), hashes, false, LogMove.END, 8))
    }

    @Test
    fun `a selected branch or tag counts as its commit`(): Unit {
        val tag = SelectedItem.TagItem(Tag(commitHash = "b2", hash = "t1", name = "refs/tags/v1"), commit("b2"))

        assertEquals(2, movedLogSelection(tag, hashes, false, LogMove.DOWN, 8))
        assertEquals(0, movedLogSelection(tag, hashes, false, LogMove.UP, 8))
    }

    @Test
    fun `uncommitted changes selected but not shown, nothing selected and an unloaded commit all select the first row`(): Unit {
        assertEquals(0, movedLogSelection(SelectedItem.UncommittedChanges, hashes, false, LogMove.DOWN, 8))
        assertEquals(0, movedLogSelection(SelectedItem.None, hashes, true, LogMove.DOWN, 8))
        assertEquals(0, movedLogSelection(item("zz"), hashes, true, LogMove.DOWN, 8))
        assertEquals(3, movedLogSelection(SelectedItem.None, hashes, true, LogMove.END, 8))
    }

    @Test
    fun `an empty log has no row to select`(): Unit {
        assertNull(movedLogSelection(SelectedItem.None, emptyList(), false, LogMove.DOWN, 8))
        assertEquals(0, movedLogSelection(SelectedItem.None, emptyList(), true, LogMove.DOWN, 8))
    }
}
