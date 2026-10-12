// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.BranchMerged
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRemoval
import dev.app.leaf.domain.models.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreeStepsTest {
    private val main = worktree("/home/me/dev/leaf", isMain = true, branch = "refs/heads/main")
    private val agent = worktree("/home/me/dev/leaf-agent1", branch = "refs/heads/agent1")

    @Test
    fun `removes a clean worktree from the main one, naming it by its relative path`() {
        val steps = removal(agent).removeSteps(RemoveWorktreeOptions())

        assertEquals(listOf(git("worktree", "remove", "../leaf-agent1")), steps)
    }

    @Test
    fun `forces the removal of a worktree with changes, and deletes its branch`() {
        val steps = removal(agent).removeSteps(RemoveWorktreeOptions(force = true, deleteBranch = true))

        assertEquals(
            listOf(
                git("worktree", "remove", "--force", "../leaf-agent1"),
                git("branch", "-D", "agent1"),
            ),
            steps,
        )
    }

    @Test
    fun `unlocks a locked worktree first, so that git still refuses its changes`() {
        val locked = agent.copy(locked = "agent running")

        val steps = removal(locked).removeSteps(RemoveWorktreeOptions(overrideLock = true))

        assertEquals(
            listOf(
                git("worktree", "unlock", "../leaf-agent1"),
                git("worktree", "remove", "../leaf-agent1"),
            ),
            steps,
        )
    }

    @Test
    fun `doesn't unlock a worktree that isn't locked`() {
        val steps = removal(agent).removeSteps(RemoveWorktreeOptions(overrideLock = true))

        assertEquals(listOf(git("worktree", "remove", "../leaf-agent1")), steps)
    }

    @Test
    fun `switches the tab to the main worktree first when it shows the one that goes`() {
        val steps = removal(agent.copy(isCurrent = true)).removeSteps(RemoveWorktreeOptions())

        assertEquals(
            listOf(WorktreeStep.SwitchTab(main.path), git("worktree", "remove", "../leaf-agent1")),
            steps,
        )
    }

    @Test
    fun `deletes no branch when the worktree has none to delete`() {
        val steps = removal(agent, branch = null).removeSteps(RemoveWorktreeOptions(deleteBranch = true))

        assertEquals(listOf(git("worktree", "remove", "../leaf-agent1")), steps)
    }

    @Test
    fun `locks with a reason, or without one when it's blank`() {
        assertEquals(
            listOf(git("worktree", "lock", "--reason", "on a USB drive", "../leaf-agent1")),
            lockWorktreeSteps(agent, main, "  on a USB drive "),
        )
        assertEquals(listOf(git("worktree", "lock", "../leaf-agent1")), lockWorktreeSteps(agent, main, " "))
    }

    @Test
    fun `unlocks and prunes from the main worktree`() {
        assertEquals(listOf(git("worktree", "unlock", "../leaf-agent1")), unlockWorktreeSteps(agent, main))
        assertEquals(listOf(git("worktree", "prune")), pruneWorktreesSteps(main))
    }

    @Test
    fun `names a worktree relative to the folder only when it's inside or next to it`() {
        assertEquals("../leaf-agent1", pathArgument("/home/me/dev/leaf-agent1", "/home/me/dev/leaf"))
        assertEquals(".claude/worktrees/a1", pathArgument("/home/me/dev/leaf/.claude/worktrees/a1", "/home/me/dev/leaf"))
        assertEquals("/tmp/agents/a1", pathArgument("/tmp/agents/a1", "/home/me/dev/leaf"))
        assertEquals("/home/me/other/a1", pathArgument("/home/me/other/a1", "/home/me/dev/leaf"))
        assertEquals("./-odd", pathArgument("/home/me/dev/leaf/-odd", "/home/me/dev/leaf"))
    }

    @Test
    fun `quotes arguments the way a shell reads them back`() {
        assertEquals("../leaf-agent1", shellQuote("../leaf-agent1"))
        assertEquals("'on a USB drive'", shellQuote("on a USB drive"))
        assertEquals("""'it'\''s mine'""", shellQuote("it's mine"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun `writes the home folder as a tilde, quoting what follows it`() {
        assertEquals("cd ~/dev/leaf", cdLine("/home/me/dev/leaf", "/home/me"))
        assertEquals("cd ~/'my repos/leaf'", cdLine("/home/me/my repos/leaf", "/home/me/"))
        assertEquals("cd /home/meow/leaf", cdLine("/home/meow/leaf", "/home/me"))
        assertEquals("cd /srv/leaf", cdLine("/srv/leaf", null))
    }

    @Test
    fun `shows a cd each time the folder changes, and other steps as comments`() {
        val steps = listOf(
            WorktreeStep.SwitchTab(main.path),
            git("worktree", "remove", "../leaf-agent1"),
            git("branch", "-D", "agent1"),
            WorktreeStep.Git("/srv/other", listOf("status")),
        )

        val lines = shellLines(steps, "/home/me") { "Switch this tab to ${it.folder.substringAfterLast('/')}" }

        assertEquals(
            listOf(
                "# Switch this tab to leaf",
                "cd ~/dev/leaf",
                "git worktree remove ../leaf-agent1",
                "git branch -D agent1",
                "cd /srv/other",
                "git status",
            ),
            lines,
        )
    }

    @Test
    fun `counts changes as lost only when the folder exists, and unknown changes as there`() {
        val clean = WorktreeStatus(0, 0, 0, 0, upstream = null, upstreamAheadBehind = null)
        val dirty = clean.copy(untracked = 1)

        assertFalse(removal(agent, status = clean).hasUncommittedChanges)
        assertTrue(removal(agent, status = dirty).hasUncommittedChanges)
        assertTrue(removal(agent, status = null).hasUncommittedChanges)
        assertFalse(removal(agent.copy(prunable = "gone"), status = null).hasUncommittedChanges)
    }

    private fun removal(
        worktree: Worktree,
        branch: String? = "agent1",
        status: WorktreeStatus? = null,
    ) = WorktreeRemoval(
        worktree = worktree,
        mainWorktree = main,
        status = status,
        lastChange = null,
        branch = branch,
        baseBranch = "refs/heads/main",
        branchMerged = BranchMerged(isMerged = false, commitsNotInBase = 1),
    )

    private fun git(vararg args: String) = WorktreeStep.Git(main.path, args.toList())

    private fun worktree(path: String, isMain: Boolean = false, branch: String? = null) = Worktree(
        path = path,
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = branch,
        isMain = isMain,
        isCurrent = false,
        isDetached = branch == null,
        isBare = false,
        locked = null,
        prunable = null,
    )
}
