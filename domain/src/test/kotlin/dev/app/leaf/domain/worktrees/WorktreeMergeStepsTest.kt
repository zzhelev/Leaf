// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.BranchMerged
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeMerge
import dev.app.leaf.domain.models.WorktreeMergeKind
import dev.app.leaf.domain.models.WorktreeMergeRefusal
import dev.app.leaf.domain.models.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreeMergeStepsTest {
    private val main = worktree("/home/me/dev/leaf", isMain = true, branch = "refs/heads/main", isCurrent = true)
    private val agent = worktree("/home/me/dev/leaf-agent1", branch = "refs/heads/agent1")
    private val clean = status()

    @Test
    fun `merges into a local base itself`() {
        assertEquals("refs/heads/main", mergeTarget("refs/heads/main", listOf("refs/heads/main", "refs/heads/agent1")))
    }

    @Test
    fun `merges into the local branch of a remote base`() {
        assertEquals("refs/heads/main", mergeTarget("refs/remotes/origin/main", listOf("refs/heads/main")))
    }

    @Test
    fun `has nothing to merge into without a base, or a local branch for a remote one`() {
        assertNull(mergeTarget(null, listOf("refs/heads/main")))
        assertNull(mergeTarget("refs/remotes/origin/main", listOf("refs/heads/agent1", "refs/heads/origin/main")))
        assertNull(mergeTarget("refs/tags/v1", listOf("refs/heads/v1")))
    }

    @Test
    fun `takes the longest local branch that a remote base ends with, as a remote's name can have a slash`() {
        val localBranches = listOf("refs/heads/x", "refs/heads/feat/x")

        assertEquals("refs/heads/feat/x", mergeTarget("refs/remotes/origin/feat/x", localBranches))
    }

    @Test
    fun `offers to merge a branch that is neither the base nor the target`() {
        assertEquals("refs/heads/agent1", agent.branchToMerge("refs/heads/main", "refs/heads/main"))
        assertNull(main.branchToMerge("refs/heads/main", "refs/heads/main"))
        assertNull(main.branchToMerge("refs/remotes/origin/main", "refs/heads/main"))
        assertNull(agent.copy(branch = null, isDetached = true).branchToMerge("refs/heads/main", "refs/heads/main"))
        assertNull(agent.branchToMerge("refs/heads/main", null))
    }

    @Test
    fun `squash-merges in the tab's worktree when it has the target`() {
        val steps = merge().mergeSteps(WorktreeMergeKind.SQUASH, "agent1\n\n- Add b")

        assertEquals(
            listOf(
                WorktreeStep.Git(main.path, listOf("merge", "--squash", "agent1"), mayStopAtConflicts = true),
                WorktreeStep.WriteSquashMessage(main.path, "agent1\n\n- Add b"),
            ),
            steps,
        )
    }

    @Test
    fun `switches the tab to the target's worktree first, and merges without a fast-forward or a commit`() {
        val tab = agent.copy(isCurrent = true)
        val target = main.copy(isCurrent = false)

        val steps = merge(targetWorktree = target, tabWorktree = tab).mergeSteps(WorktreeMergeKind.MERGE, "")

        assertEquals(
            listOf(
                WorktreeStep.SwitchTab(target.path),
                WorktreeStep.Git(
                    target.path,
                    listOf("merge", "--no-ff", "--no-commit", "agent1"),
                    mayStopAtConflicts = true,
                ),
            ),
            steps,
        )
    }

    @Test
    fun `checks the target out in the tab's worktree when no worktree has it`() {
        val tab = main.copy(branch = "refs/heads/other")

        val steps = merge(targetWorktree = null, tabWorktree = tab, tabStatus = clean)
            .mergeSteps(WorktreeMergeKind.SQUASH, "message")

        assertEquals(
            listOf(
                WorktreeStep.Git(tab.path, listOf("switch", "main")),
                WorktreeStep.Git(tab.path, listOf("merge", "--squash", "agent1"), mayStopAtConflicts = true),
                WorktreeStep.WriteSquashMessage(tab.path, "message"),
            ),
            steps,
        )
    }

    @Test
    fun `names the branch as the merge was told to`() {
        val steps = merge(branchArgument = "refs/heads/agent1").mergeSteps(WorktreeMergeKind.MERGE, "")

        val args = (steps.single() as WorktreeStep.Git).args

        assertEquals(listOf("merge", "--no-ff", "--no-commit", "refs/heads/agent1"), args)
    }

    @Test
    fun `can merge when the target's worktree has only unstaged and untracked changes`() {
        val merge = merge(targetStatus = status(unstaged = 2, untracked = 1))

        assertNull(merge.refusal(WorktreeMergeKind.SQUASH))
        assertNull(merge.refusal(WorktreeMergeKind.MERGE))
    }

    @Test
    fun `refuses a branch with nothing to merge`() {
        assertEquals(WorktreeMergeRefusal.NothingToMerge, merge(commitCount = 0).refusal(WorktreeMergeKind.MERGE))
    }

    @Test
    fun `refuses to squash a branch whose changes are already in the target, but merges it`() {
        val merge = merge(branchMerged = BranchMerged(isMerged = true, commitsNotInBase = 2))

        assertEquals(WorktreeMergeRefusal.AlreadyInTarget, merge.refusal(WorktreeMergeKind.SQUASH))
        assertNull(merge.refusal(WorktreeMergeKind.MERGE))
    }

    @Test
    fun `refuses while the target's worktree has staged changes or conflicts, which a squash would take in`() {
        assertEquals(
            WorktreeMergeRefusal.TargetHasChanges(main, staged = 1, conflicted = 0),
            merge(targetStatus = status(staged = 1)).refusal(WorktreeMergeKind.SQUASH),
        )
        assertEquals(
            WorktreeMergeRefusal.TargetHasChanges(main, staged = 0, conflicted = 2),
            merge(targetStatus = status(conflicted = 2)).refusal(WorktreeMergeKind.MERGE),
        )
    }

    @Test
    fun `refuses when the target's worktree can't be read or is missing`() {
        assertEquals(
            WorktreeMergeRefusal.TargetStatusUnknown(main),
            merge(targetStatus = null).refusal(WorktreeMergeKind.SQUASH),
        )

        val missing = main.copy(prunable = "gitdir file points to non-existent location")

        assertEquals(
            WorktreeMergeRefusal.TargetWorktreeMissing(missing),
            merge(targetWorktree = missing).refusal(WorktreeMergeKind.SQUASH),
        )
    }

    @Test
    fun `refuses while a worktree rebases the target`() {
        val rebasing = main.copy(branch = null, isDetached = true, rebasingBranch = "refs/heads/main")

        assertEquals(
            WorktreeMergeRefusal.TargetInUse(rebasing, WorktreeBranchUse.Rebasing),
            merge(targetWorktree = null, targetUser = rebasing).refusal(WorktreeMergeKind.MERGE),
        )
    }

    @Test
    fun `refuses to check the target out in the branch's own worktree, or in one with changes`() {
        val tabOnBranch = agent.copy(isCurrent = true)

        assertEquals(
            WorktreeMergeRefusal.TabWorktreeIsBranchWorktree,
            merge(targetWorktree = null, tabWorktree = tabOnBranch, tabStatus = clean)
                .refusal(WorktreeMergeKind.SQUASH),
        )

        for (tabStatus in listOf(status(staged = 1), status(unstaged = 1), status(conflicted = 1), null)) {
            assertEquals(
                WorktreeMergeRefusal.TabWorktreeHasChanges(main),
                merge(targetWorktree = null, tabStatus = tabStatus).refusal(WorktreeMergeKind.SQUASH),
            )
        }

        assertNull(merge(targetWorktree = null, tabStatus = status(untracked = 3)).refusal(WorktreeMergeKind.SQUASH))
    }

    @Test
    fun `a branch with one commit is squashed with that commit's message`() {
        val commit = commit("Add login form\n\nWith an email field.\n")

        assertEquals("Add login form\n\nWith an email field.", squashMessage("refs/heads/agent1", listOf(commit), 1))
    }

    @Test
    fun `a branch with several commits is squashed with its name, then their subjects, oldest first`() {
        val commits = listOf(commit("Add login form\n\nBody"), commit("Validate the email"))

        assertEquals(
            "feat/login\n\n- Add login form\n- Validate the email",
            squashMessage("refs/heads/feat/login", commits, 2),
        )
    }

    @Test
    fun `the squash message counts the commits that it leaves out`() {
        val commits = listOf(commit("Newest"))

        assertEquals("agent1\n\n- (3 older commits)\n- Newest", squashMessage("refs/heads/agent1", commits, 4))
        assertEquals("agent1\n\n- (1 older commit)\n- Newest", squashMessage("refs/heads/agent1", commits, 2))
    }

    @Test
    fun `a squash merge is in progress while SQUASH_MSG is there and something is staged or conflicted`() {
        val staged = Status(staged = listOf(StatusEntry("f", StatusType.MODIFIED, EntryType.STAGED)))
        val conflicted = Status(unstaged = listOf(StatusEntry("f", StatusType.CONFLICTING)))
        val unstaged = Status(unstaged = listOf(StatusEntry("f", StatusType.MODIFIED)))

        assertTrue(isSquashMergeInProgress(RepositoryState.SAFE, "agent1", staged))
        assertTrue(isSquashMergeInProgress(RepositoryState.SAFE, "agent1", conflicted))
        assertFalse(isSquashMergeInProgress(RepositoryState.SAFE, "agent1", unstaged))
        assertFalse(isSquashMergeInProgress(RepositoryState.SAFE, null, staged))
        assertFalse(isSquashMergeInProgress(RepositoryState.MERGING, "agent1", staged))
        assertFalse(isSquashMergeInProgress(RepositoryState.SAFE, "agent1", null))
    }

    @Test
    fun `the commands show writing the squash message as a comment`() {
        val steps = merge().mergeSteps(WorktreeMergeKind.SQUASH, "message")

        val lines = shellLines(steps, "/home/me") { "Put the commit message above in SQUASH_MSG" }

        assertEquals(
            listOf("cd ~/dev/leaf", "git merge --squash agent1", "# Put the commit message above in SQUASH_MSG"),
            lines,
        )
    }

    private fun merge(
        targetWorktree: Worktree? = main,
        targetStatus: WorktreeStatus? = clean,
        targetUser: Worktree? = null,
        tabWorktree: Worktree = main,
        tabStatus: WorktreeStatus? = null,
        branchArgument: String = "agent1",
        commitCount: Int = 2,
        branchMerged: BranchMerged? = BranchMerged(isMerged = false, commitsNotInBase = commitCount),
    ) = WorktreeMerge(
        worktree = agent,
        branch = "refs/heads/agent1",
        branchArgument = branchArgument,
        target = "refs/heads/main",
        targetWorktree = targetWorktree,
        targetStatus = targetStatus,
        targetUser = targetUser,
        tabWorktree = tabWorktree,
        tabStatus = tabStatus,
        branchStatus = clean,
        lastChange = null,
        commits = emptyList(),
        commitCount = commitCount,
        branchMerged = branchMerged,
    )

    private fun status(staged: Int = 0, unstaged: Int = 0, untracked: Int = 0, conflicted: Int = 0) =
        WorktreeStatus(staged, unstaged, untracked, conflicted, upstream = null, upstreamAheadBehind = null)

    private fun commit(message: String) = Commit(
        hash = "0123456789abcdef0123456789abcdef01234567",
        message = message,
        committer = Identity("Me", "me@example.com"),
        author = Identity("Me", "me@example.com"),
        date = 0,
        parentsHashes = emptyList(),
    )

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
