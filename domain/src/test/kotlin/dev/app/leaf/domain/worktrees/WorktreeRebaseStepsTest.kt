// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRebase
import dev.app.leaf.domain.models.WorktreeRebaseRefusal
import dev.app.leaf.domain.models.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorktreeRebaseStepsTest {
    private val main = worktree("/home/me/dev/leaf", isMain = true, branch = "refs/heads/main", isCurrent = true)
    private val agent = worktree("/home/me/dev/leaf-agent1", branch = "refs/heads/agent1")

    @Test
    fun `offers to rebase a branch that is neither the base nor the base's local branch`() {
        assertEquals("refs/heads/agent1", agent.branchToRebase("refs/heads/main", "refs/heads/main"))
        assertEquals("refs/heads/agent1", agent.branchToRebase("refs/remotes/origin/main", "refs/heads/main"))
        // A remote base without a local branch: nothing to leave out
        assertEquals("refs/heads/agent1", agent.branchToRebase("refs/remotes/origin/main", null))

        assertNull(main.branchToRebase("refs/heads/main", "refs/heads/main"))
        // Rebasing main onto origin/main would be a pull
        assertNull(main.branchToRebase("refs/remotes/origin/main", "refs/heads/main"))
        assertNull(agent.copy(branch = null, isDetached = true).branchToRebase("refs/heads/main", "refs/heads/main"))
        assertNull(agent.branchToRebase(null, null))
    }

    @Test
    fun `rebases in the branch's worktree, and switches the tab there only if it stops at conflicts`() {
        assertEquals(
            listOf(
                WorktreeStep.Git(agent.path, listOf("rebase", "--no-autostash", "main"), mayStopAtConflicts = true),
                WorktreeStep.SwitchTab(agent.path, onlyIfStoppedAtConflicts = true),
            ),
            rebase().rebaseSteps(),
        )
    }

    @Test
    fun `doesn't switch when the tab already shows the branch's worktree`() {
        val steps = rebase(worktree = agent.copy(isCurrent = true)).rebaseSteps()

        assertEquals(
            listOf(WorktreeStep.Git(agent.path, listOf("rebase", "--no-autostash", "main"), mayStopAtConflicts = true)),
            steps,
        )
    }

    @Test
    fun `names the base as the reader found it`() {
        val steps = rebase(base = "refs/remotes/origin/main", baseArgument = "origin/main").rebaseSteps()

        assertEquals(listOf("rebase", "--no-autostash", "origin/main"), (steps.first() as WorktreeStep.Git).args)
    }

    @Test
    fun `shows the conditional switch as a comment after the command`() {
        val lines = shellLines(rebase().rebaseSteps(), home = "/home/me") { "If it stops at conflicts, switch" }

        assertEquals(
            listOf("cd ~/dev/leaf-agent1", "git rebase --no-autostash main", "# If it stops at conflicts, switch"),
            lines,
        )
    }

    @Test
    fun `rebases a clean worktree that is behind the base, untracked files or not`() {
        assertNull(rebase().refusal())
        assertNull(rebase(status = status(untracked = 3)).refusal())
        // No commits of its own: the branch moves up to the base
        assertNull(rebase(commitCount = 0).refusal())
    }

    @Test
    fun `refuses uncommitted changes to tracked files, counted together`() {
        assertEquals(WorktreeRebaseRefusal.HasChanges(1), rebase(status = status(unstaged = 1)).refusal())
        assertEquals(
            WorktreeRebaseRefusal.HasChanges(6),
            rebase(status = status(staged = 1, unstaged = 2, untracked = 4, conflicted = 3)).refusal(),
        )
        // The changes come first: they would stop git whether the branch is up to date or not
        assertEquals(
            WorktreeRebaseRefusal.HasChanges(2),
            rebase(status = status(staged = 2), baseCommitCount = 0).refusal(),
        )
    }

    @Test
    fun `refuses a branch that already has every commit of the base`() {
        assertEquals(WorktreeRebaseRefusal.UpToDate, rebase(baseCommitCount = 0).refusal())
    }

    @Test
    fun `refuses a worktree whose changes or folder can't be read`() {
        assertEquals(WorktreeRebaseRefusal.StatusUnknown, rebase(status = null).refusal())
        assertEquals(
            WorktreeRebaseRefusal.WorktreeMissing,
            rebase(worktree = agent.copy(prunable = "gitdir file points to non-existent location"), status = null)
                .refusal(),
        )
    }

    private fun rebase(
        worktree: Worktree = agent,
        base: String = "refs/heads/main",
        baseArgument: String = "main",
        status: WorktreeStatus? = status(),
        commitCount: Int = 2,
        baseCommitCount: Int = 1,
    ) = WorktreeRebase(
        worktree = worktree,
        branch = "refs/heads/agent1",
        base = base,
        baseArgument = baseArgument,
        status = status,
        lastChange = null,
        commitCount = commitCount,
        baseCommitCount = baseCommitCount,
    )

    private fun status(staged: Int = 0, unstaged: Int = 0, untracked: Int = 0, conflicted: Int = 0) =
        WorktreeStatus(staged, unstaged, untracked, conflicted, upstream = null, upstreamAheadBehind = null)

    private fun worktree(path: String, isMain: Boolean = false, branch: String? = null, isCurrent: Boolean = false) =
        Worktree(
            path = path,
            headSha = "0123456789abcdef0123456789abcdef01234567",
            branch = branch,
            isMain = isMain,
            isCurrent = isCurrent,
            isDetached = branch == null,
            isBare = false,
            locked = null,
            prunable = null,
        )
}
