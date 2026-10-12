// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorktreeReviewTest {
    private val agent = Worktree(
        path = "/dev/leaf-agent1",
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = "refs/heads/agent1",
        isMain = false,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )

    @Test
    fun `reviews a worktree's branch against a local or a remote base`() {
        assertEquals("refs/heads/agent1", agent.branchToReview("refs/heads/main"))
        assertEquals("refs/heads/agent1", agent.branchToReview("refs/remotes/origin/main"))
    }

    @Test
    fun `has nothing to review on the base itself, when detached, or without a base`() {
        assertNull(agent.copy(branch = "refs/heads/main").branchToReview("refs/heads/main"))
        assertNull(agent.copy(branch = null, isDetached = true, rebasingBranch = "refs/heads/agent1").branchToReview("refs/heads/main"))
        assertNull(agent.branchToReview(null))
    }
}
