// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

private const val START = "1111111111111111111111111111111111111111"
private const val BASE = "2222222222222222222222222222222222222222"
private const val WORK = "3333333333333333333333333333333333333333"
private const val REBASED = "4444444444444444444444444444444444444444"

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 1_800_000_000_000L

class StaleWorktreesTest {
    @Test
    fun `a branch's own work comes from commits made on it, and from rebases that kept some`() {
        // The messages are git 2.54's, then JGit 7.7's
        val entries = listOf(
            entry(START, "branch: Created from HEAD"),
            entry(WORK, "commit: Work"),
            entry(WORK, "commit (amend): Work"),
            entry(WORK, "commit (initial): Work"),
            entry(WORK, "commit (merge): Merge branch 'main' into agent"),
            entry(WORK, "cherry-pick: Work"),
            entry(WORK, """revert: Revert "Work""""),
            entry(WORK, "am: Work"),
            entry(REBASED, "rebase (finish): refs/heads/agent onto $BASE"),
            entry(REBASED, "rebase -i (finish): refs/heads/agent onto $BASE"),
            entry(REBASED, "rebase finished: refs/heads/agent onto $BASE"),
        )

        assertEquals(entries.drop(1).map { it.newId }, commitsOfItsOwn(entries))
    }

    @Test
    fun `moving the branch to the base's commits brings no work of its own`() {
        val entries = listOf(
            entry(START, "branch: Created from HEAD"),
            // "Rebase onto main" with a fresh worktree: the branch ends at the commit it was rebased onto
            entry(BASE, "rebase (finish): refs/heads/agent onto $BASE"),
            entry(BASE, "rebase finished: refs/heads/agent onto $BASE"),
            entry(BASE, "merge main: Fast-forward"),
            entry(BASE, "merge main: Merge made by the 'ort' strategy."),
            entry(BASE, "pull -q . main: Fast-forward"),
            entry(BASE, "reset: moving to main"),
            entry(BASE, "rebase: Fast-forward"),
            entry(BASE, "branch: Reset to main"),
        )

        assertEquals(emptyList<String>(), commitsOfItsOwn(entries))
    }

    @Test
    fun `only a linked worktree on a branch other than the base and its local branch is marked merged`() {
        val agent = worktree("refs/heads/agent")

        assertEquals("refs/heads/agent", agent.branchToMarkMerged("refs/heads/main"))
        assertEquals("refs/heads/agent", agent.branchToMarkMerged("refs/remotes/origin/main"))

        assertNull(agent.branchToMarkMerged(null))
        assertNull(agent.branchToMarkMerged("refs/heads/agent"))
        assertNull(worktree("refs/heads/main").branchToMarkMerged("refs/remotes/origin/main"), "the base's local branch")
        assertNull(worktree("refs/heads/agent").copy(isMain = true).branchToMarkMerged("refs/heads/main"))
        assertNull(worktree(null).branchToMarkMerged("refs/heads/main"), "detached")
        assertNull(
            worktree(null).copy(rebasingBranch = "refs/heads/agent").branchToMarkMerged("refs/heads/main"),
            "rebasing",
        )
    }

    @Test
    fun `the settings' days decide which worktrees show as idle`() {
        val list = WorktreeList(
            WorktreeBaseBranch(automatic = "refs/heads/main"),
            listOf(info(idleSince = null), info(idleSince = NOW - 10 * DAY), info(idleSince = NOW - 30 * DAY)),
        )

        assertEquals(listOf(null, NOW - 10 * DAY, NOW - 30 * DAY), list.withIdle(7, NOW).idleSince())
        assertEquals(listOf(null, null, NOW - 30 * DAY), list.withIdle(14, NOW).idleSince())
        assertEquals(listOf(null, null, NOW - 30 * DAY), list.withIdle(30, NOW).idleSince())
        assertEquals(listOf(null, null, null), list.withIdle(90, NOW).idleSince())
        assertEquals(listOf(null, null, null), list.withIdle(0, NOW).idleSince(), "never")

        // The list only knows of worktrees untouched for a week: fewer days in the settings file count as a week
        val withFiveDays = list.copy(worktrees = list.worktrees + info(idleSince = NOW - 5 * DAY))
        assertEquals(listOf(null, NOW - 10 * DAY, NOW - 30 * DAY, null), withFiveDays.withIdle(3, NOW).idleSince())
    }

    @Test
    fun `a list without idle worktrees stays the same object, which recomposes nothing`() {
        val list = WorktreeList(WorktreeBaseBranch(automatic = null), listOf(info(idleSince = null)))

        assertSame(list, list.withIdle(14, NOW))
    }

    @Test
    fun `a row shows how long nothing changed in an idle worktree`() {
        val list = WorktreeList(
            WorktreeBaseBranch(automatic = "refs/heads/main"),
            listOf(info(idleSince = null, lastCommitTime = NOW - 2 * DAY), info(idleSince = NOW - 21 * DAY)),
        )

        val rows = worktreeRows(list, filter = "", NOW)

        assertEquals(listOf("2d", null), rows.map { it.ageLabel })
        assertEquals(listOf(null, "3w"), rows.map { it.idleLabel })
    }

    private fun WorktreeList.idleSince() = worktrees.map { it.idleSince }

    private fun entry(newId: String, comment: String) = BranchReflogEntry(newId, comment)

    private fun info(idleSince: Long?, lastCommitTime: Long? = null) =
        WorktreeInfo(worktree("refs/heads/agent"), null, null, lastCommitTime, idleSince = idleSince)

    private fun worktree(branch: String?) = Worktree(
        path = "/repo/.claude/worktrees/agent",
        headSha = WORK,
        branch = branch,
        isMain = false,
        isCurrent = false,
        isDetached = branch == null,
        isBare = false,
        locked = null,
        prunable = null,
    )
}
