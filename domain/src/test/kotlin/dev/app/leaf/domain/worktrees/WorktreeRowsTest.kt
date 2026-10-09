// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val DAY = 86_400_000L
private const val NOW = 100 * DAY
private const val HASH = "0123456789abcdef0123456789abcdef01234567"

class WorktreeRowsTest {
    private val main = worktree("/repo", branch = "refs/heads/main", isMain = true)
    private val agent = worktree("/repo/.claude/worktrees/busy-jang", branch = "refs/heads/claude/busy-jang")
    private val feature = worktree("/work/repo-login", rebasingBranch = "refs/heads/feature/login")

    @Test
    fun `gives each worktree a row in git's order, named after its folder`() {
        val list = WorktreeList(
            "refs/heads/main",
            listOf(info(main, lastCommitTime = NOW), info(agent, lastCommitTime = NOW - 3 * DAY), info(feature)),
        )

        val rows = worktreeRows(list, filter = "", nowMillis = NOW)

        assertEquals(
            listOf(
                WorktreeRow(list.worktrees[0], "repo", WorktreeHeadLabel.OnBranch("main"), "now"),
                WorktreeRow(list.worktrees[1], "busy-jang", WorktreeHeadLabel.OnBranch("claude/busy-jang"), "3d"),
                WorktreeRow(list.worktrees[2], "repo-login", WorktreeHeadLabel.Rebasing("feature/login"), null),
            ),
            rows,
        )
    }

    @Test
    fun `filters by folder name, path or branch, ignoring case`() {
        val list = WorktreeList("refs/heads/main", listOf(info(main), info(agent), info(feature)))

        fun names(filter: String) = worktreeRows(list, filter, NOW).map { it.name }

        assertEquals(listOf("busy-jang"), names("BUSY"))
        assertEquals(listOf("busy-jang"), names(".claude"))
        assertEquals(listOf("busy-jang"), names("claude/"))
        assertEquals(listOf("repo-login"), names("feature/log"))
        assertEquals(listOf("repo", "busy-jang", "repo-login"), names("  "))
        assertEquals(emptyList<String>(), names("nothing"))
    }

    @Test
    fun `says what a worktree has checked out`() {
        assertEquals(WorktreeHeadLabel.OnBranch("main"), main.headLabel())
        assertEquals(WorktreeHeadLabel.Rebasing("feature/login"), feature.headLabel())
        assertEquals(
            WorktreeHeadLabel.Bisecting("fix"),
            worktree("/wt", bisectingBranch = "refs/heads/fix").headLabel(),
        )
        assertEquals(WorktreeHeadLabel.Detached("0123456"), worktree("/wt").headLabel())
        assertEquals(WorktreeHeadLabel.Bare, worktree("/repo.git", isBare = true).headLabel())
    }

    @Test
    fun `names a worktree next to a branch unless its folder repeats the branch`() {
        assertNull(agent.nameNextTo("refs/heads/claude/busy-jang"))
        assertNull(worktree("/work/feature").nameNextTo("refs/heads/feature"))
        assertEquals("repo-login", feature.nameNextTo("refs/heads/feature/login"))
        assertEquals("repo", main.nameNextTo("refs/heads/main"))
    }

    private fun worktree(
        path: String,
        branch: String? = null,
        isMain: Boolean = false,
        isBare: Boolean = false,
        rebasingBranch: String? = null,
        bisectingBranch: String? = null,
    ) = Worktree(
        path = path,
        headSha = if (isBare) null else HASH,
        branch = branch,
        isMain = isMain,
        isCurrent = false,
        isDetached = branch == null && !isBare,
        isBare = isBare,
        locked = null,
        prunable = null,
        rebasingBranch = rebasingBranch,
        bisectingBranch = bisectingBranch,
    )

    private fun info(worktree: Worktree, lastCommitTime: Long? = null) =
        WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = lastCommitTime)
}
