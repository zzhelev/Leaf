// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RepositorySelectionStateTest {
    @Test
    fun `an open tab is saved as its git dir`() {
        val state = RepositorySelectionState.Open("/repos/main/.git/worktrees/feature")

        assertEquals("/repos/main/.git/worktrees/feature", state.pathToPersist(initialPath = "/repos/main-feature"))
    }

    @Test
    fun `a tab that hasn't loaded yet is saved as the path it was created with`() {
        assertEquals("/repos/main", RepositorySelectionState.Unknown.pathToPersist(initialPath = "/repos/main"))
    }

    @Test
    fun `a new tab that hasn't loaded yet is not saved`() {
        assertNull(RepositorySelectionState.Unknown.pathToPersist(initialPath = null))
    }

    @Test
    fun `a tab on the welcome page is not saved`() {
        assertNull(RepositorySelectionState.None.pathToPersist(initialPath = "/repos/main"))
    }

    @Test
    fun `a tab switches worktree when it opens another git dir`(): Unit = runBlocking {
        val states = flowOf(
            RepositorySelectionState.Unknown,
            RepositorySelectionState.Opening("/repos/main"),
            RepositorySelectionState.Open("/repos/main/.git"),
            RepositorySelectionState.Open("/repos/main/.git"),
            RepositorySelectionState.Open("/repos/main/.git/worktrees/feature"),
            RepositorySelectionState.Open("/repos/main/.git"),
        )

        assertEquals(
            listOf("/repos/main/.git/worktrees/feature", "/repos/main/.git"),
            states.worktreeSwitches().toList(),
        )
    }

    @Test
    fun `opening the tab's first repository isn't a switch`(): Unit = runBlocking {
        val states = flowOf(RepositorySelectionState.Unknown, RepositorySelectionState.Open("/repos/main/.git"))

        assertEquals(emptyList<String>(), states.worktreeSwitches().toList())
    }
}
