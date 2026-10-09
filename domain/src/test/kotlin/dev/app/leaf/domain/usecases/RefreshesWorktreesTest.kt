// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RefreshesWorktreesTest {
    @Test
    fun `refreshes the worktrees with the branches, the log or the status, or alone`() {
        val refreshing = DataToRefresh.entries.filter { refreshesWorktrees(listOf(it)) }

        assertEquals(
            listOf(
                DataToRefresh.ALL,
                DataToRefresh.BRANCHES,
                DataToRefresh.LOG,
                DataToRefresh.STATUS,
                DataToRefresh.WORKTREES,
            ),
            refreshing,
        )
    }

    @Test
    fun `refreshes them when any of the data does`() {
        assertEquals(true, refreshesWorktrees(listOf(DataToRefresh.STASHES, DataToRefresh.STATUS)))
        assertEquals(false, refreshesWorktrees(listOf(DataToRefresh.STASHES, DataToRefresh.TAGS)))
        assertEquals(false, refreshesWorktrees(emptyList()))
    }
}
