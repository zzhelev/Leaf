// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.NewWorktreeContext
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeCreationSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NewWorktreeStepsTest {
    private val main = Worktree(
        path = "/home/me/dev/leaf",
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = "refs/heads/main",
        isMain = true,
        isCurrent = true,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )

    @Test
    fun `creates a branch from the start point, next to the repository`() {
        val steps = request(NewWorktreeBranch.Create("fix-auth"), "main").steps()

        assertEquals(listOf(git("worktree", "add", "-b", "fix-auth", "../leaf-fix-auth", "main")), steps)
    }

    @Test
    fun `checks out a branch that no worktree uses`() {
        val steps = request(NewWorktreeBranch.CheckOut("agent1"), "agent1", folder = "/home/me/dev/leaf-agent1").steps()

        assertEquals(listOf(git("worktree", "add", "../leaf-agent1", "agent1")), steps)
    }

    @Test
    fun `tracks a remote branch picked in a menu, and doesn't track a remote base`() {
        val tracked = request(NewWorktreeBranch.Create("feature"), "origin/feature", NewWorktreeTracking.TRACK).steps()
        val untracked = request(NewWorktreeBranch.Create("fix-auth"), "origin/main", NewWorktreeTracking.NO_TRACK).steps()

        assertEquals(
            listOf(git("worktree", "add", "--track", "-b", "feature", "../leaf-fix-auth", "origin/feature")),
            tracked,
        )
        assertEquals(
            listOf(git("worktree", "add", "--no-track", "-b", "fix-auth", "../leaf-fix-auth", "origin/main")),
            untracked,
        )
    }

    @Test
    fun `copies the ignored files, then switches the tab`() {
        val steps = request(
            NewWorktreeBranch.Create("fix-auth"),
            "main",
            copyFiles = listOf(".env", "local.properties"),
            switchTab = true,
        ).steps()

        assertEquals(
            listOf(
                git("worktree", "add", "-b", "fix-auth", "../leaf-fix-auth", "main"),
                WorktreeStep.CopyFiles(main.path, "/home/me/dev/leaf-fix-auth", listOf(".env", "local.properties")),
                WorktreeStep.SwitchTab("/home/me/dev/leaf-fix-auth"),
            ),
            steps,
        )
    }

    @Test
    fun `names the folder after the repository and the branch, next to it or in the repository's folder`() {
        assertEquals("/home/me/dev/leaf-fix-auth", newWorktreeFolder(main, null, "fix-auth"))
        assertEquals("/home/me/dev/leaf-claude-fix-auth", newWorktreeFolder(main, null, "claude/fix-auth"))
        assertEquals("/home/me/agents/leaf-fix-auth", newWorktreeFolder(main, "/home/me/agents", " fix-auth "))
        assertEquals("/srv/leaf-x", newWorktreeFolder(main.copy(path = "/srv/leaf.git", isBare = true), null, "x"))
    }

    @Test
    fun `starts from the base branch, without tracking a remote one`() {
        val local = NewWorktreeStart.Base.startPoint(context(baseBranch = "refs/heads/main"))
        val remote = NewWorktreeStart.Base.startPoint(context(baseBranch = "refs/remotes/origin/main"))

        assertEquals(NewWorktreeStartPoint("main", "main", NewWorktreeTracking.GIT_DEFAULT), local)
        assertEquals(NewWorktreeStartPoint("origin/main", "origin/main", NewWorktreeTracking.NO_TRACK), remote)
    }

    @Test
    fun `without a base branch, starts from the tab's branch, or its commit when detached`() {
        val onBranch = NewWorktreeStart.Base.startPoint(context(currentBranch = branch("refs/heads/feature")))
        val detached = NewWorktreeStart.Base.startPoint(context(currentCommit = "abcdef0123456789"))

        assertEquals(NewWorktreeStartPoint("feature", "feature", NewWorktreeTracking.GIT_DEFAULT), onBranch)
        assertEquals(NewWorktreeStartPoint("abcdef0", "abcdef0123456789", NewWorktreeTracking.GIT_DEFAULT), detached)
    }

    @Test
    fun `offers to check out a local branch only when no worktree uses it`() {
        val agent = branch("refs/heads/agent1")

        val unused = NewWorktreeStart.LocalBranch(agent).startPoint(context())
        val used = NewWorktreeStart.LocalBranch(agent).startPoint(context(branchesInUse = setOf("refs/heads/agent1")))

        assertEquals("agent1", unused.branchToCheckOut)
        assertNull(used.branchToCheckOut)
        assertEquals("agent1", used.startPoint)
    }

    @Test
    fun `suggests a remote branch's name, which the new branch tracks`() {
        val start = NewWorktreeStart.RemoteBranch(branch("refs/remotes/origin/feature/login", isLocal = false))

        assertEquals(
            NewWorktreeStartPoint(
                label = "origin/feature/login",
                startPoint = "origin/feature/login",
                tracking = NewWorktreeTracking.TRACK,
                suggestedName = "feature/login",
            ),
            start.startPoint(context()),
        )
    }

    @Test
    fun `starts from a commit by its hash`() {
        val identity = Identity("A", "a@example.invalid")
        val commit = Commit("fedcba9876543210fedcba9876543210fedcba98", "Fix", identity, identity, 0, emptyList())

        val start = NewWorktreeStart.Commit(commit).startPoint(context())

        assertEquals(NewWorktreeStartPoint("fedcba9", commit.hash, NewWorktreeTracking.GIT_DEFAULT), start)
    }

    @Test
    fun `shows copying as a comment`() {
        val steps = listOf(WorktreeStep.CopyFiles(main.path, "/home/me/dev/leaf-x", listOf(".env")))

        assertEquals(listOf("# copy"), shellLines(steps, "/home/me") { "copy" })
    }

    private fun request(
        branch: NewWorktreeBranch,
        startPoint: String,
        tracking: NewWorktreeTracking = NewWorktreeTracking.GIT_DEFAULT,
        folder: String = "/home/me/dev/leaf-fix-auth",
        copyFiles: List<String> = emptyList(),
        switchTab: Boolean = false,
    ) = NewWorktreeRequest(main, startPoint, tracking, branch, folder, copyFiles, switchTab)

    private fun git(vararg args: String) = WorktreeStep.Git(main.path, args.toList())

    private fun branch(name: String, isLocal: Boolean = true) = Branch(hash = "0".repeat(40), name = name, isLocal = isLocal)

    private fun context(
        baseBranch: String? = null,
        currentBranch: Branch? = null,
        currentCommit: String? = null,
        branchesInUse: Set<String> = emptySet(),
    ) = NewWorktreeContext(
        mainWorktree = main,
        baseBranch = baseBranch,
        currentBranch = currentBranch,
        currentCommit = currentCommit,
        localBranches = emptySet(),
        branchesInUse = branchesInUse,
        settings = WorktreeCreationSettings(),
    )
}
