// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.models.RECENT_CHANGE_MS
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.rewrite.PlanPlace
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where a commit editor's plan would replay if it stops (fork-only, B5 of `docs/fork/commit-editor.md`): the worktree
 * that has the branch, the tab's own first, else the tab's; its uncommitted tracked changes, read again; and another
 * worktree's change of the last few minutes.
 */
class GetPlanPlaceUseCaseTest {
    private val now = 10_000_000L
    private val statuses = mutableMapOf<String, WorktreeStatus>()
    private val lastChanges = mutableMapOf<String, Long>()
    private val statusRead = mutableListOf<String>()

    private val useCase = GetPlanPlaceUseCase(
        getWorktreeStatusGitAction = object : IGetWorktreeStatusGitAction {
            override suspend fun invoke(worktreePath: String): Either<WorktreeStatus, GitError> {
                statusRead += worktreePath
                return Either.Ok(statuses[worktreePath] ?: status())
            }
        },
        getWorktreeActivityGitAction = object : IGetWorktreeActivityGitAction {
            override suspend fun invoke(worktreePath: String, notBefore: Long?): Either<Long?, GitError> =
                Either.Ok(lastChanges[worktreePath])
        },
    )

    private fun status(staged: Int = 0, unstaged: Int = 0, untracked: Int = 0) =
        WorktreeStatus(staged, unstaged, untracked, conflicted = 0, upstream = null, upstreamAheadBehind = null)

    private fun worktree(path: String, branch: String?, isCurrent: Boolean = false, isMain: Boolean = false) = WorktreeInfo(
        worktree = Worktree(
            path = path,
            headSha = "a".repeat(40),
            branch = branch,
            isMain = isMain,
            isCurrent = isCurrent,
            isDetached = branch == null,
            isBare = false,
            locked = null,
            prunable = null,
        ),
        status = null,
        aheadBehindBase = null,
        lastCommitTime = null,
    )

    private fun list(vararg worktrees: WorktreeInfo) = WorktreeList(WorktreeBaseBranch("refs/heads/main"), worktrees.toList())

    @Test
    fun `the tab's own worktree with the branch gives its tracked changes, untracked files left out`(): Unit = runBlocking {
        statuses["/repo"] = status(staged = 1, unstaged = 2, untracked = 5)

        val place = useCase(list(worktree("/repo", "refs/heads/feat", isCurrent = true, isMain = true)), "refs/heads/feat", now)

        assertEquals(PlanPlace(worktreeName = "repo", isTabs = true, uncommitted = 3), place)
    }

    @Test
    fun `another worktree with the branch gives its name, its changes, and a change of the last minutes`(): Unit = runBlocking {
        statuses["/wt/agent"] = status(unstaged = 1)
        lastChanges["/wt/agent"] = now - RECENT_CHANGE_MS / 2
        val worktrees = list(
            worktree("/repo", "refs/heads/main", isCurrent = true, isMain = true),
            worktree("/wt/agent", "refs/heads/feat"),
        )

        assertEquals(
            PlanPlace(worktreeName = "agent", isTabs = false, uncommitted = 1, recentChange = now - RECENT_CHANGE_MS / 2),
            useCase(worktrees, "refs/heads/feat", now),
        )

        lastChanges["/wt/agent"] = now - RECENT_CHANGE_MS * 2

        assertEquals(null, useCase(worktrees, "refs/heads/feat", now).recentChange)
    }

    @Test
    fun `with no worktree on the branch it replays in the tab's, whose changes count`(): Unit = runBlocking {
        statuses["/repo"] = status(staged = 2)

        val place = useCase(list(worktree("/repo", "refs/heads/main", isCurrent = true, isMain = true)), "refs/heads/feat", now)

        assertEquals(PlanPlace(worktreeName = null, isTabs = true, uncommitted = 2), place)
        assertEquals(listOf("/repo"), statusRead)
    }

    @Test
    fun `a branch checked out twice replays in the tab's worktree`(): Unit = runBlocking {
        val worktrees = list(
            worktree("/wt/other", "refs/heads/feat"),
            worktree("/repo", "refs/heads/feat", isCurrent = true, isMain = true),
        )

        assertEquals("repo", useCase(worktrees, "refs/heads/feat", now).worktreeName)
        assertEquals(true, useCase(worktrees, "refs/heads/feat", now).isTabs)
    }
}
