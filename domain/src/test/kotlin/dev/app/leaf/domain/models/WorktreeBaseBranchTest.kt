// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorktreeBaseBranchTest {
    @Test
    fun `compares to the automatic branch when none is chosen`() {
        assertEquals("refs/heads/main", WorktreeBaseBranch(automatic = "refs/heads/main").effective)
        assertNull(WorktreeBaseBranch(automatic = null).effective)
    }

    @Test
    fun `compares to the chosen branch, local or remote, while it exists`() {
        for (chosen in listOf("refs/heads/develop", "refs/remotes/origin/main")) {
            val base = WorktreeBaseBranch("refs/heads/main", chosen, chosenExists = true)

            assertEquals(chosen, base.effective)
        }

        assertEquals(
            "refs/heads/develop",
            WorktreeBaseBranch(automatic = null, chosen = "refs/heads/develop", chosenExists = true).effective,
        )
    }

    @Test
    fun `falls back to the automatic branch while the chosen one doesn't exist`() {
        val base = WorktreeBaseBranch("refs/heads/main", "refs/heads/release", chosenExists = false)

        assertEquals("refs/heads/main", base.effective)
        assertNull(WorktreeBaseBranch(null, "refs/heads/release", chosenExists = false).effective)
    }

    @Test
    fun `a worktree list is compared to the effective branch`() {
        val base = WorktreeBaseBranch("refs/heads/main", "refs/heads/develop", chosenExists = true)

        assertEquals("refs/heads/develop", WorktreeList(base, emptyList()).baseBranch)
    }
}
