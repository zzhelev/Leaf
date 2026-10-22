// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.cherrypick

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.worktrees.WorktreeStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BranchCherryPickTest {
    private val main = Branch(hash = "m".repeat(40), name = "refs/heads/main", isLocal = true)
    private val fix = Branch(hash = "a4880d17".padEnd(40, '0'), name = "refs/heads/fix/unread-bar", isLocal = true)
    private val remoteFix = Branch(hash = "b".repeat(40), name = "refs/remotes/origin/fix/unread-bar", isLocal = false)
    private val detached = Branch(hash = "d".repeat(40), name = "HEAD", isLocal = true)

    // The branch menus' item

    @Test
    fun `one commit is picked at once, named by the branch's tip`() {
        val item = branchCherryPickItem(fix, main, BranchAhead(1, tipIsMerge = false))

        assertEquals(BranchCherryPickItem.OneCommit(fix.hash), item)
        assertEquals("a4880d1", (item as BranchCherryPickItem.OneCommit).shortHash)
    }

    @Test
    fun `several commits open the dialog, with their count`() {
        assertEquals(BranchCherryPickItem.Commits(3), branchCherryPickItem(fix, main, BranchAhead(3, tipIsMerge = false)))
        assertEquals(BranchCherryPickItem.Commits(2), branchCherryPickItem(fix, main, BranchAhead(2, tipIsMerge = true)))
    }

    @Test
    fun `a count that git couldn't give opens the dialog without one`() {
        assertEquals(BranchCherryPickItem.Commits(null), branchCherryPickItem(fix, main, BranchAhead(null, false)))
    }

    @Test
    fun `remote branches get it too`() {
        assertEquals(
            BranchCherryPickItem.OneCommit(remoteFix.hash),
            branchCherryPickItem(remoteFix, main, BranchAhead(1, tipIsMerge = false)),
        )
    }

    @Test
    fun `nothing to pick, no branch to pick onto, or the current branch itself, gets no item`() {
        // A branch that the current branch already has
        assertNull(branchCherryPickItem(fix, main, null))
        assertNull(branchCherryPickItem(fix, main, BranchAhead(0, tipIsMerge = false)))
        // Its only new commit is a merge, which git can't pick without a parent
        assertNull(branchCherryPickItem(fix, main, BranchAhead(1, tipIsMerge = true)))
        // A detached HEAD, as for Merge and Rebase, and an empty repository
        assertNull(branchCherryPickItem(fix, detached, BranchAhead(1, tipIsMerge = false)))
        assertNull(branchCherryPickItem(fix, null, BranchAhead(1, tipIsMerge = false)))
        assertNull(branchCherryPickItem(main, main, BranchAhead(1, tipIsMerge = false)))
    }

    @Test
    fun `branches are found by their full ref`() {
        val ahead = BranchesAhead(mapOf(fix.name to BranchAhead(2, false)))

        assertEquals(BranchAhead(2, false), ahead.of(fix))
        assertNull(ahead.of(remoteFix))
    }

    // The dialog

    private fun commit(hash: String, message: String, parents: Int = 1) = Commit(
        hash = hash.padEnd(40, '0'),
        message = message,
        committer = Identity("A", "a@example.invalid"),
        author = Identity("A", "a@example.invalid"),
        date = 0,
        parentsHashes = List(parents) { "p$it".padEnd(40, '0') },
    )

    private val one = commit("1111", "CAPS-1260: Count unread messages\n\nWith a body.")
    private val two = commit("2222", "CAPS-1260: Show the unread bar")
    private val merge = commit("3333", "Merge branch 'main' into fix", parents = 2)
    private val applied = commit("4444", "Already on main")
    private val wip = commit("5555", "WIP: log unread counts")

    private fun pick(
        candidates: List<CherryPickCandidate> = listOf(
            CherryPickCandidate(one),
            CherryPickCandidate(merge, CherryPickLeftOut.MERGE),
            CherryPickCandidate(two),
            CherryPickCandidate(applied, CherryPickLeftOut.ALREADY_APPLIED),
            CherryPickCandidate(wip),
        ),
        commitCount: Int = candidates.size,
        currentBranch: String = main.name,
        state: RepositoryState = RepositoryState.SAFE,
        isSequenceInProgress: Boolean = false,
        status: WorktreeStatus? = WorktreeStatus(0, 0, 0, 0, null, null),
    ) = BranchCherryPick(
        branch = fix,
        currentBranch = currentBranch,
        workingTree = "/repo",
        candidates = candidates,
        commitCount = commitCount,
        repositoryState = state,
        isSequenceInProgress = isSequenceInProgress,
        status = status,
    )

    private val all = setOf(one.hash, two.hash, wip.hash)

    @Test
    fun `merge commits and those already applied can't be picked`() {
        assertEquals(listOf(one, two, wip), pick().pickable)
    }

    @Test
    fun `the plan picks the ticked commits in the branch's order, whatever order they were ticked in`() {
        val plan = pick().cherryPickPlan(linkedSetOf(wip.hash, one.hash, merge.hash), asOneCommit = false)

        assertEquals(listOf(one.hash, wip.hash), plan?.commits, "The merge commit can't be ticked")
        assertEquals(
            listOf(WorktreeStep.Git("/repo", listOf("cherry-pick", one.hash, wip.hash), mayStopAtConflicts = true)),
            plan?.steps,
        )
        assertFalse(plan!!.noCommit)
    }

    @Test
    fun `as one commit, the changes are staged together and the messages go to SQUASH_MSG`() {
        val plan = pick().cherryPickPlan(setOf(one.hash, two.hash), asOneCommit = true)!!

        assertTrue(plan.noCommit)
        assertEquals(listOf("cherry-pick", "--no-commit", one.hash, two.hash), cherryPickArguments(plan.commits, true))
        assertEquals("fix/unread-bar\n\n- CAPS-1260: Count unread messages\n- CAPS-1260: Show the unread bar", plan.squashMessage)
        assertEquals(
            listOf(
                WorktreeStep.Git("/repo", listOf("cherry-pick", "--no-commit", one.hash, two.hash), true),
                WorktreeStep.WriteSquashMessage("/repo", plan.squashMessage!!),
            ),
            plan.steps,
        )
    }

    @Test
    fun `one commit as one commit keeps its own message`() {
        val plan = pick().cherryPickPlan(setOf(one.hash), asOneCommit = true)!!

        assertEquals("CAPS-1260: Count unread messages\n\nWith a body.", plan.squashMessage)
    }

    @Test
    fun `a remote branch's message names its remote`() {
        val plan = pick().copy(branch = remoteFix).cherryPickPlan(setOf(one.hash, two.hash), asOneCommit = true)!!

        assertTrue(plan.squashMessage!!.startsWith("origin/fix/unread-bar\n"), plan.squashMessage)
    }

    @Test
    fun `no plan without a commit to pick`() {
        assertNull(pick().cherryPickPlan(emptySet(), asOneCommit = false))
        assertNull(pick().cherryPickPlan(setOf(merge.hash, applied.hash), asOneCommit = false))
    }

    @Test
    fun `refusals come in their order, and none when it can run`() {
        assertNull(pick().refusal(all))

        assertEquals(CherryPickRefusal.DetachedHead, pick(currentBranch = "HEAD", state = RepositoryState.MERGING).refusal(all))
        assertEquals(
            CherryPickRefusal.TooManyCommits(MAX_CHERRY_PICK_COMMITS + 1),
            pick(candidates = emptyList(), commitCount = MAX_CHERRY_PICK_COMMITS + 1).refusal(all),
        )
        assertEquals(CherryPickRefusal.NothingToPick, pick(candidates = emptyList(), commitCount = 0).refusal(all))
        assertEquals(
            CherryPickRefusal.NothingToPick,
            pick(candidates = listOf(CherryPickCandidate(merge, CherryPickLeftOut.MERGE))).refusal(all),
        )
        assertEquals(CherryPickRefusal.OperationInProgress, pick(state = RepositoryState.REBASING_MERGE).refusal(all))
        assertEquals(CherryPickRefusal.OperationInProgress, pick(isSequenceInProgress = true).refusal(all))
        assertEquals(CherryPickRefusal.StatusUnknown, pick(status = null).refusal(all))
        assertEquals(
            CherryPickRefusal.StagedChanges(3),
            pick(status = WorktreeStatus(2, 5, 7, 1, null, null)).refusal(all),
            "Staged files and conflicts count, unstaged and untracked ones don't",
        )
        assertEquals(CherryPickRefusal.StagedChanges(1), pick(status = WorktreeStatus(1, 0, 0, 0, null, null)).refusal(all))
        assertEquals(CherryPickRefusal.StagedChanges(1), pick(status = WorktreeStatus(0, 0, 0, 1, null, null)).refusal(all))
        assertNull(pick(status = WorktreeStatus(0, 5, 7, 0, null, null)).refusal(all))
        assertEquals(CherryPickRefusal.NoneSelected, pick().refusal(emptySet()))
        assertEquals(CherryPickRefusal.NoneSelected, pick().refusal(setOf(merge.hash)))
    }

    // git's sequencer

    @Test
    fun `a cherry-pick's todo gives the commits left, the stopped one first`() {
        val todo = "pick dea00a5 feat: two\npick 277db12 feat: three\n"

        assertEquals(CherryPickSequence(listOf("dea00a5", "277db12"), noCommit = false), cherryPickSequenceOf(todo, null))
    }

    @Test
    fun `abbreviated commands, comments and blank lines are read as git writes them`() {
        val todo = "# comment\n\np dea00a5 two\n  pick 277db12 three  \n"

        assertEquals(listOf("dea00a5", "277db12"), cherryPickSequenceOf(todo, null)?.remaining)
    }

    @Test
    fun `no-commit comes from opts`() {
        val todo = "pick dea00a5 two\n"

        assertTrue(cherryPickSequenceOf(todo, "[options]\n\tno-commit = true\n")!!.noCommit)
        assertFalse(cherryPickSequenceOf(todo, "[options]\n\tno-commit = false\n")!!.noCommit)
        assertFalse(cherryPickSequenceOf(todo, "[options]\n\tallow-ff = true\n")!!.noCommit)
    }

    @Test
    fun `a revert's sequence, or an empty todo, isn't a cherry-pick`() {
        assertNull(cherryPickSequenceOf("revert dea00a5 two\n", null))
        assertNull(cherryPickSequenceOf("pick dea00a5 two\nrevert 277db12 three\n", null))
        assertNull(cherryPickSequenceOf("", null))
        assertNull(cherryPickSequenceOf("# nothing\n", null))
    }
}
