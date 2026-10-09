// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BranchWorktreeBadgeTest {
    @Test
    fun `the branch name gets all it needs when it fits next to the icon`() {
        assertEquals(80, branchNameWidth(available = 200, branchFull = 80, reserved = 22))
        assertEquals(178, branchNameWidth(available = 200, branchFull = 178, reserved = 22))
    }

    @Test
    fun `the branch name is cut to leave room for the icon`() {
        assertEquals(178, branchNameWidth(available = 200, branchFull = 300, reserved = 22))
        assertEquals(200, branchNameWidth(available = 200, branchFull = 300, reserved = 0))
        assertEquals(0, branchNameWidth(available = 10, branchFull = 300, reserved = 22))
    }

    @Test
    fun `the worktree name gets all it needs, or what's left down to the minimum`() {
        assertEquals(60, worktreeNameWidth(space = 100, nameFull = 60, minName = 44))
        assertEquals(60, worktreeNameWidth(space = 60, nameFull = 60, minName = 44))
        assertEquals(50, worktreeNameWidth(space = 50, nameFull = 60, minName = 44))
        assertEquals(44, worktreeNameWidth(space = 44, nameFull = 60, minName = 44))
    }

    @Test
    fun `the worktree name is left out below the minimum, unless all of it fits`() {
        assertNull(worktreeNameWidth(space = 43, nameFull = 60, minName = 44))
        assertNull(worktreeNameWidth(space = -5, nameFull = 60, minName = 44))
        assertEquals(20, worktreeNameWidth(space = 30, nameFull = 20, minName = 44))
    }
}
