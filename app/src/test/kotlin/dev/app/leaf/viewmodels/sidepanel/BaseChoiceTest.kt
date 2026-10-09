// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels.sidepanel

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The menus that choose the worktrees' base branch stay out of repositories with only one worktree. */
class BaseChoiceTest {
    private val base = WorktreeBaseBranch(automatic = "refs/heads/main", chosen = "refs/heads/develop", chosenExists = true)

    @Test
    fun `offers the base to choose once the repository has a linked worktree`() {
        val list = WorktreeList(base, listOf(info("/repo", isMain = true), info("/repo/.claude/worktrees/agent")))

        assertEquals(base, list.baseChoice())
    }

    @Test
    fun `offers nothing for a repository with only its main worktree`() {
        val list = WorktreeList(base, listOf(info("/repo", isMain = true)))

        assertNull(list.baseChoice())
        assertEquals("refs/heads/develop", list.baseBranch, "Its row is still compared to the base")
    }

    private fun info(path: String, isMain: Boolean = false) = WorktreeInfo(
        worktree = Worktree(
            path = path,
            headSha = "0123456789abcdef0123456789abcdef01234567",
            branch = "refs/heads/main",
            isMain = isMain,
            isCurrent = isMain,
            isDetached = false,
            isBare = false,
            locked = null,
            prunable = null,
        ),
        status = null,
        aheadBehindBase = null,
        lastCommitTime = null,
    )
}
