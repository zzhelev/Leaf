// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.AgentSessionLock
import dev.app.leaf.domain.models.AgentTool
import dev.app.leaf.domain.models.CleanupCandidate
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeCleanup
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val REPO = "/repo"
private const val HEAD = "3333333333333333333333333333333333333333"

private val CLEAN = WorktreeStatus(0, 0, 0, 0, upstream = null, upstreamAheadBehind = null)
private val DIRTY = CLEAN.copy(unstaged = 2, untracked = 1)

class WorktreeCleanupTest {
    private val main = info(worktree("$REPO", "refs/heads/main", isMain = true), status = CLEAN)

    @Test
    fun `offers linked worktrees whose work is in the base, and missing ones`() {
        val merged = info(worktree("$REPO-merged", "refs/heads/merged"), merged = MergedIntoBase.COMMITS)
        val squashed = info(worktree("$REPO-squashed", "refs/heads/squashed"), merged = MergedIntoBase.CHANGES)
        val missing = info(worktree("$REPO-missing", "refs/heads/missing", prunable = "gone"), status = null)
        val fresh = info(worktree("$REPO-fresh", "refs/heads/fresh"))
        val bare = info(worktree("$REPO.git", null, isBare = true, isMain = true), merged = MergedIntoBase.COMMITS)

        val candidates = cleanup(main, merged, squashed, missing, fresh).candidates

        assertEquals(listOf(merged, squashed, missing), candidates.map { it.info })
        assertTrue(candidates.all { it.isCheckedAtFirst })
        assertEquals(emptyList<CleanupCandidate>(), cleanup(bare).candidates)
    }

    @Test
    fun `leaves unchecked what would lose work or may still be in use`() {
        val dirty = info(worktree("$REPO-dirty", "refs/heads/dirty"), status = DIRTY, merged = MergedIntoBase.COMMITS)
        val unknown = info(worktree("$REPO-unknown", "refs/heads/unknown"), status = null, merged = MergedIntoBase.COMMITS)
        val recent = info(worktree("$REPO-recent", "refs/heads/recent"), merged = MergedIntoBase.COMMITS)
        val locked = info(worktree("$REPO-locked", "refs/heads/locked", locked = "usb"), merged = MergedIntoBase.COMMITS)

        val candidates = cleanup(main, dirty, unknown, recent, locked, recentChanges = mapOf("$REPO-recent" to 1L))
            .candidates

        assertEquals(listOf(false, false, false, false), candidates.map { it.isCheckedAtFirst })
        assertEquals(3, candidates[0].changes)
        assertTrue(candidates[0].hasUncommittedChanges)
        assertNull(candidates[1].changes)
        assertTrue(candidates[1].hasUncommittedChanges)
        assertEquals(1L, candidates[2].recentChange)
        assertTrue(candidates[3].hasLockToLookAt)
        assertEquals(emptySet<String>(), candidates.checkedAtFirst())
    }

    @Test
    fun `a missing worktree has nothing to lose, and keeps a branch whose work isn't in the base`() {
        val missing = info(worktree("$REPO-missing", "refs/heads/missing", prunable = "gone"), status = null)
        val mergedMissing = info(
            worktree("$REPO-merged", "refs/heads/merged", prunable = "gone"),
            status = null,
            merged = MergedIntoBase.COMMITS,
        )

        val (unmerged, merged) = cleanup(main, missing, mergedMissing).candidates

        assertFalse(unmerged.hasUncommittedChanges)
        assertTrue(unmerged.isCheckedAtFirst)
        assertNull(unmerged.branch)
        assertEquals("merged", merged.branch)
    }

    @Test
    fun `an agent's ended session lock is checked, a running one can't be, and comes last`() {
        val running = info(
            worktree("$REPO-running", "refs/heads/running", locked = "claude session a", running = true),
            merged = MergedIntoBase.COMMITS,
        )
        val ended = info(
            worktree("$REPO-ended", "refs/heads/ended", locked = "claude session b", running = false),
            merged = MergedIntoBase.COMMITS,
        )
        val dirty = info(worktree("$REPO-dirty", "refs/heads/dirty"), status = DIRTY, merged = MergedIntoBase.CHANGES)
        val list = list(main, running, dirty, ended)

        val candidates = WorktreeCleanup(list).candidates

        assertEquals(listOf(ended, dirty, running), candidates.map { it.info })
        assertTrue(candidates[0].isCheckedAtFirst)
        assertFalse(candidates[0].hasLockToLookAt)
        assertTrue(candidates[2].isInUse)
        assertFalse(candidates[2].isCheckedAtFirst)
        assertEquals(2, list.cleanupCount())
    }

    @Test
    fun `nothing to clean up in a repository with only fresh worktrees`() {
        val fresh = info(worktree("$REPO-fresh", "refs/heads/fresh"))

        assertEquals(0, list(main, fresh).cleanupCount())
    }

    @Test
    fun `removes each worktree, then its branch, from the main worktree`() {
        val merged = info(worktree("$REPO/.claude/worktrees/a1", "refs/heads/worktree-a1"), merged = MergedIntoBase.CHANGES)
        val dirty = info(worktree("$REPO-dirty", "refs/heads/dirty"), status = DIRTY, merged = MergedIntoBase.COMMITS)
        val missing = info(worktree("$REPO-missing", "refs/heads/missing", prunable = "gone"), status = null)
        val locked = info(worktree("$REPO-locked", "refs/heads/locked", locked = "usb"), merged = MergedIntoBase.COMMITS)

        val candidates = cleanup(main, merged, dirty, missing, locked).candidates
        val steps = cleanupSteps(main.worktree, candidates, deleteBranches = true)

        assertEquals(
            listOf(
                git("worktree", "remove", ".claude/worktrees/a1"),
                git("branch", "-D", "worktree-a1"),
                git("worktree", "remove", "../repo-missing"),
                git("worktree", "remove", "--force", "../repo-dirty"),
                git("branch", "-D", "dirty"),
                git("worktree", "unlock", "../repo-locked"),
                git("worktree", "remove", "../repo-locked"),
                git("branch", "-D", "locked"),
            ),
            steps,
        )
    }

    @Test
    fun `keeps the branches when asked, and never removes a worktree in use`() {
        val merged = info(worktree("$REPO-merged", "refs/heads/merged"), merged = MergedIntoBase.COMMITS)
        val running = info(
            worktree("$REPO-running", "refs/heads/running", locked = "claude session a", running = true),
            merged = MergedIntoBase.COMMITS,
        )

        val steps = cleanupSteps(main.worktree, cleanup(main, merged, running).candidates, deleteBranches = false)

        assertEquals(listOf(git("worktree", "remove", "../repo-merged")), steps)
    }

    @Test
    fun `the tab switches to the main worktree first when it shows one that goes`() {
        val current = info(
            worktree("$REPO-merged", "refs/heads/merged", isCurrent = true),
            merged = MergedIntoBase.COMMITS,
        )

        val steps = cleanupSteps(main.worktree, cleanup(main, current).candidates, deleteBranches = true)

        assertEquals(WorktreeStep.SwitchTab(REPO), steps.first())
        assertEquals(3, steps.size)
    }

    private fun git(vararg args: String) = WorktreeStep.Git(REPO, args.toList())

    private fun cleanup(vararg infos: WorktreeInfo, recentChanges: Map<String, Long> = emptyMap()) =
        WorktreeCleanup(list(*infos), recentChanges)

    private fun list(vararg infos: WorktreeInfo) =
        WorktreeList(WorktreeBaseBranch(automatic = "refs/heads/main"), infos.toList())

    private fun info(worktree: Worktree, status: WorktreeStatus? = CLEAN, merged: MergedIntoBase? = null) =
        WorktreeInfo(worktree, status, aheadBehindBase = null, lastCommitTime = null, merged = merged)

    private fun worktree(
        path: String,
        branch: String?,
        isMain: Boolean = false,
        isBare: Boolean = false,
        isCurrent: Boolean = false,
        locked: String? = null,
        prunable: String? = null,
        running: Boolean? = null,
    ) = Worktree(
        path = path,
        headSha = HEAD,
        branch = branch,
        isMain = isMain,
        isCurrent = isCurrent,
        isDetached = branch == null,
        isBare = isBare,
        locked = locked,
        prunable = prunable,
        agentLock = running?.let { AgentSessionLock(AgentTool.CLAUDE_CODE, "session", isRunning = it) },
    )
}
