// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.branches

import dev.app.leaf.domain.models.Branch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A selection holds the local branches, or one remote's (fork-only, follows upstream issue 116). */
class RemoteBranchSelectionTest {
    private val localA = "refs/heads/a"
    private val localB = "refs/heads/b"
    private val originA = "refs/remotes/origin/a"
    private val originB = "refs/remotes/origin/b"
    private val originC = "refs/remotes/origin/feature/c"
    private val upstreamA = "refs/remotes/upstream/a"
    private val originRows = listOf(originA, originB, originC)

    @Test
    fun `a branch's group is the local branches or its remote's`() {
        assertEquals("refs/heads/", selectionGroupOf(localA))
        assertEquals("refs/heads/", selectionGroupOf("refs/heads/claude/x"))
        assertEquals("refs/remotes/origin/", selectionGroupOf(originA))
        assertEquals("refs/remotes/origin/", selectionGroupOf(originC))
        assertEquals("refs/remotes/upstream/", selectionGroupOf(upstreamA))

        assertNull(remoteOfSelectionGroup("refs/heads/"))
        assertEquals("origin", remoteOfSelectionGroup("refs/remotes/origin/"))
        assertEquals("origin", remoteOfSelectionGroup(selectionGroupOf(originC)))
    }

    @Test
    fun `the selection's group is its branches', and none while nothing is selected`() {
        assertNull(BranchSelection().group)
        assertEquals("refs/heads/", BranchSelection(setOf(localA, localB)).group)
        assertEquals("refs/remotes/origin/", BranchSelection(setOf(originA, originB)).group)
    }

    @Test
    fun `toggling a remote's branch while local ones are selected starts over with it`() {
        val selection = BranchSelection(setOf(localA, localB), anchor = localB).afterToggle(originA, current = null)

        assertEquals(setOf(originA), selection.selected)
        assertEquals(originA, selection.anchor)
    }

    @Test
    fun `toggling a local branch while a remote's are selected starts over with it`() {
        val selection = BranchSelection(setOf(originA, originB), anchor = originB).afterToggle(localA, current = null)

        assertEquals(setOf(localA), selection.selected)
    }

    @Test
    fun `toggling another remote's branch starts over with it`() {
        val selection = BranchSelection(setOf(originA, originB), anchor = originB).afterToggle(upstreamA, current = null)

        assertEquals(setOf(upstreamA), selection.selected)
    }

    @Test
    fun `the first toggle on a remote's branch starts from the log's branch only when it's of that remote`() {
        assertEquals(setOf(originA, originB), BranchSelection().afterToggle(originB, current = originA).selected)
        assertEquals(setOf(originB), BranchSelection().afterToggle(originB, current = localA).selected)
        assertEquals(setOf(originB), BranchSelection().afterToggle(originB, current = upstreamA).selected)
    }

    @Test
    fun `toggles within a remote add and remove as with local branches`() {
        val selection = BranchSelection()
            .afterToggle(originA, current = null)
            .afterToggle(originB, current = null)
            .afterToggle(originC, current = null)
            .afterToggle(originB, current = null)

        assertEquals(setOf(originA, originC), selection.selected)
    }

    @Test
    fun `a range in a remote's rows covers the branches between the anchor and the click`() {
        val selection = BranchSelection().afterToggle(originA, current = null).afterRange(originC, originRows, current = null)

        assertEquals(setOf(originA, originB, originC), selection.selected)
    }

    @Test
    fun `a range in a remote's rows from a local anchor takes only the branch clicked`() {
        val selection = BranchSelection(setOf(localA, localB), anchor = localB).afterRange(originB, originRows, current = null)

        assertEquals(setOf(originB), selection.selected)
        assertEquals(originB, selection.anchor)
    }

    @Test
    fun `selecting a remote folder's branches replaces a selection of local ones`() {
        val selection = BranchSelection(setOf(localA, localB), anchor = localA).afterAdding(listOf(originA, originB), current = null)

        assertEquals(setOf(originA, originB), selection.selected)
        assertEquals(originB, selection.anchor)
    }

    @Test
    fun `selecting a remote folder's branches adds to the same remote's`() {
        val selection = BranchSelection(setOf(originC), anchor = originC).afterAdding(listOf(originA, originB), current = null)

        assertEquals(setOf(originA, originB, originC), selection.selected)
    }

    @Test
    fun `an empty folder changes nothing`() {
        val selection = BranchSelection(setOf(originA, originB), anchor = originB)

        assertEquals(selection, selection.afterAdding(emptyList(), current = localA))
    }

    @Test
    fun `a refresh drops a remote's branches that a fetch with prune removed`() {
        val selection = BranchSelection(setOf(originA, originB), anchor = originB).pruned(listOf(localA, originA))

        assertEquals(BranchSelection(setOf(originA), anchor = null), selection)
    }

    @Test
    fun `a remote's folder holds its branches by their names without the remote`() {
        val branches = listOf("origin/feature/c", "origin/a", "origin/feature/deep/d").map {
            Branch(hash = "", name = "refs/remotes/$it", isLocal = false)
        }

        assertEquals(
            listOf("refs/remotes/origin/feature/c", "refs/remotes/origin/feature/deep/d"),
            branchesInFolder(branches, "feature", pinnedBranch = null),
        )
    }
}
