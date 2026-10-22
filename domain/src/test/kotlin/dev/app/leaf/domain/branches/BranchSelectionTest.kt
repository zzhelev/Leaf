// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.models.Branch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BranchSelectionTest {
    private val a = "refs/heads/a"
    private val b = "refs/heads/b"
    private val c = "refs/heads/c"
    private val d = "refs/heads/d"
    private val e = "refs/heads/e"
    private val shown = listOf(a, b, c, d, e)

    @Test
    fun `a plain click leaves no selection here and remembers where it was`() {
        val selection = BranchSelection(setOf(a, b), anchor = a).afterPlainClick(c)

        assertEquals(BranchSelection(emptySet(), anchor = c), selection)
    }

    @Test
    fun `with nothing selected here the branch the log has selected is the one highlighted`() {
        val selection = BranchSelection()

        assertTrue(selection.isHighlighted(a, current = a))
        assertFalse(selection.isHighlighted(b, current = a))
        assertFalse(selection.isHighlighted(a, current = null))
    }

    @Test
    fun `with a selection only its branches are highlighted, not the log's`() {
        val selection = BranchSelection(setOf(b, c), anchor = c)

        assertTrue(selection.isHighlighted(b, current = a))
        assertTrue(selection.isHighlighted(c, current = a))
        assertFalse(selection.isHighlighted(a, current = a))
    }

    @Test
    fun `the first toggle starts from the branch the log has selected`() {
        val selection = BranchSelection().afterToggle(c, current = a)

        assertEquals(setOf(a, c), selection.selected)
        assertEquals(c, selection.anchor)
    }

    @Test
    fun `the first toggle without a branch in the log selects just that branch`() {
        assertEquals(setOf(c), BranchSelection().afterToggle(c, current = null).selected)
    }

    @Test
    fun `toggling adds a branch and takes a selected one out`() {
        val added = BranchSelection(setOf(a, b), anchor = b).afterToggle(c, current = a)
        val removed = added.afterToggle(a, current = a)

        assertEquals(setOf(a, b, c), added.selected)
        assertEquals(setOf(b, c), removed.selected)
        assertEquals(a, removed.anchor)
    }

    @Test
    fun `toggling the log's only branch when nothing is selected here leaves it highlighted as before`() {
        val selection = BranchSelection().afterToggle(a, current = a)

        assertTrue(selection.selected.isEmpty())
        assertTrue(selection.isHighlighted(a, current = a))
    }

    @Test
    fun `a range takes the branches shown between the anchor and the click`() {
        val selection = BranchSelection(setOf(b), anchor = b).afterRange(d, shown, current = null)

        assertEquals(setOf(b, c, d), selection.selected)
        assertEquals(b, selection.anchor, "The next Shift-click ranges from the same place")
    }

    @Test
    fun `a range goes upwards too`() {
        val selection = BranchSelection(setOf(d), anchor = d).afterRange(b, shown, current = null)

        assertEquals(setOf(b, c, d), selection.selected)
    }

    @Test
    fun `a range keeps what was selected outside it`() {
        val selection = BranchSelection(setOf(a, d), anchor = d).afterRange(e, shown, current = null)

        assertEquals(setOf(a, d, e), selection.selected)
    }

    @Test
    fun `a second range ranges from the same anchor`() {
        val first = BranchSelection(setOf(b), anchor = b).afterRange(d, shown, current = null)
        val second = first.afterRange(c, shown, current = null)

        assertEquals(setOf(b, c, d), second.selected, "A range never takes branches out")
        assertEquals(b, second.anchor)
    }

    @Test
    fun `the first range starts from the branch the log has selected`() {
        val selection = BranchSelection().afterRange(c, shown, current = a)

        assertEquals(setOf(a, b, c), selection.selected)
        assertEquals(a, selection.anchor)
    }

    @Test
    fun `a range skips the branches that aren't shown, such as a collapsed folder's`() {
        // b and c are inside a closed folder: they aren't in the shown list
        val rows = listOf(a, d, e)

        val selection = BranchSelection(setOf(a), anchor = a).afterRange(e, rows, current = null)

        assertEquals(setOf(a, d, e), selection.selected)
    }

    @Test
    fun `a range from an anchor that isn't shown selects just the branch clicked`() {
        val selection = BranchSelection(setOf(b), anchor = b).afterRange(e, listOf(a, c, d, e), current = null)

        assertEquals(setOf(b, e), selection.selected)
        assertEquals(e, selection.anchor)
    }

    @Test
    fun `a range without an anchor or a log branch selects just the branch clicked`() {
        assertEquals(setOf(c), BranchSelection().afterRange(c, shown, current = null).selected)
    }

    @Test
    fun `a range ends at a branch that isn't shown by taking only it`() {
        val selection = BranchSelection(setOf(a), anchor = a).afterRange("refs/heads/hidden", shown, current = null)

        assertEquals(setOf(a, "refs/heads/hidden"), selection.selected)
    }

    @Test
    fun `selecting a folder's branches adds them to what is selected`() {
        val selection = BranchSelection(setOf(a), anchor = a).afterAdding(listOf(c, d), current = null)

        assertEquals(setOf(a, c, d), selection.selected)
    }

    @Test
    fun `selecting a folder's branches starts from the log's branch too`() {
        assertEquals(setOf(a, c, d), BranchSelection().afterAdding(listOf(c, d), current = a).selected)
    }

    @Test
    fun `a refresh drops the branches that are gone and keeps the rest`() {
        val selection = BranchSelection(setOf(a, b, c), anchor = b).pruned(existing = listOf(a, c, d))

        assertEquals(setOf(a, c), selection.selected)
        assertNull(selection.anchor, "The anchor was deleted")
    }

    @Test
    fun `a refresh that drops nothing gives the same selection`() {
        val selection = BranchSelection(setOf(a, b), anchor = b)

        assertSame(selection, selection.pruned(existing = shown))
    }

    @Test
    fun `a refresh of every branch gone leaves nothing selected`() {
        assertEquals(BranchSelection(), BranchSelection(setOf(a, b), anchor = a).pruned(existing = emptyList()))
    }

    @Test
    fun `the batch menu is for a branch among two or more selected`() {
        assertTrue(BranchSelection(setOf(a, b)).isMultiSelectionOf(a))
        assertFalse(BranchSelection(setOf(a, b)).isMultiSelectionOf(c), "That branch isn't selected: its own menu")
        assertFalse(BranchSelection(setOf(a)).isMultiSelectionOf(a), "One branch is the usual selection")
        assertFalse(BranchSelection().isMultiSelectionOf(a))
    }

    @Test
    fun `Cmd toggles on macOS and Ctrl everywhere`() {
        assertTrue(togglesSelection(isCtrlPressed = true, isMetaPressed = false, isMac = false))
        assertTrue(togglesSelection(isCtrlPressed = true, isMetaPressed = false, isMac = true))
        assertTrue(togglesSelection(isCtrlPressed = false, isMetaPressed = true, isMac = true))
        assertFalse(togglesSelection(isCtrlPressed = false, isMetaPressed = true, isMac = false), "Meta is the Windows key")
        assertFalse(togglesSelection(isCtrlPressed = false, isMetaPressed = false, isMac = true))
    }

    @Test
    fun `the keys held decide what a click does, and Shift wins`() {
        fun click(shift: Boolean = false, ctrl: Boolean = false, meta: Boolean = false, mac: Boolean = false) =
            branchClickOf(shift, ctrl, meta, mac)

        assertEquals(BranchClick.Plain, click())
        assertEquals(BranchClick.Toggle, click(ctrl = true))
        assertEquals(BranchClick.Toggle, click(meta = true, mac = true))
        assertEquals(BranchClick.Plain, click(meta = true, mac = false))
        assertEquals(BranchClick.Range, click(shift = true))
        assertEquals(BranchClick.Range, click(shift = true, ctrl = true))
        assertEquals(BranchClick.Range, click(shift = true, meta = true, mac = true))
    }

    private fun branch(name: String) = Branch(hash = "", name = "refs/heads/$name", isLocal = true)

    @Test
    fun `a folder holds the branches with its prefix, nested ones included, in the order given`() {
        val branches = listOf("claude/b", "main", "claude/a/deep", "feature/x", "claudex/y", "claude-b").map(::branch)

        assertEquals(
            listOf("refs/heads/claude/b", "refs/heads/claude/a/deep"),
            branchesInFolder(branches, "claude", pinnedBranch = null),
        )
        assertEquals(emptyList<String>(), branchesInFolder(branches, "missing", pinnedBranch = null))
        assertEquals(emptyList<String>(), branchesInFolder(branches, "main", pinnedBranch = null), "A branch without a prefix is no folder")
    }

    @Test
    fun `the current branch kept on top is outside its folder, so it isn't selected with it`() {
        val branches = listOf("claude/a", "claude/current", "claude/b").map(::branch)

        assertEquals(
            listOf("refs/heads/claude/a", "refs/heads/claude/b"),
            branchesInFolder(branches, "claude", pinnedBranch = branches[1]),
        )
        assertEquals(
            listOf("refs/heads/claude/a", "refs/heads/claude/current", "refs/heads/claude/b"),
            branchesInFolder(branches, "claude", pinnedBranch = null),
        )
    }
}
