// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dev.app.leaf.domain.models.NewWorktreeContext
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeCreationSettings
import dev.app.leaf.domain.models.WorktreeInclude
import dev.app.leaf.domain.worktrees.NewWorktreeBranch
import dev.app.leaf.domain.worktrees.NewWorktreeStartPoint
import dev.app.leaf.domain.worktrees.NewWorktreeTracking
import dev.app.leaf.domain.worktrees.WorktreeStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What the new worktree dialog lets the user create. */
class NewWorktreeStateTest {
    @TempDir
    lateinit var tempDir: File

    private val main = Worktree(
        path = "/dev/leaf",
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = "refs/heads/main",
        isMain = true,
        isCurrent = true,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )

    private val context = NewWorktreeContext(
        mainWorktree = main,
        baseBranch = "refs/heads/main",
        currentBranch = null,
        currentCommit = null,
        localBranches = setOf("refs/heads/main", "refs/heads/agent1"),
        branchesInUse = setOf("refs/heads/main"),
        settings = WorktreeCreationSettings(),
    )

    private val fromMain = NewWorktreeStartPoint("main", "main", NewWorktreeTracking.GIT_DEFAULT)
    private val fromAgent1 = NewWorktreeStartPoint("agent1", "agent1", NewWorktreeTracking.GIT_DEFAULT, "agent1")

    @Test
    fun `creates a new branch once git accepts its name`() {
        val state = state(branchName = " fix-auth ", folder = "/dev/leaf-fix-auth")

        val request = state.request
        assertNotNull(request)
        assertEquals(NewWorktreeBranch.Create("fix-auth"), request!!.branch)
        assertEquals("main", request.startPoint)
        assertEquals("/dev/leaf-fix-auth", request.folder)
    }

    @Test
    fun `waits for git's check of the name, and stops at a name it rejects or that a branch has`() {
        assertNull(state(branchName = "fix", nameRejected = null).request)
        assertNull(state(branchName = "bad..name", nameRejected = true).request)

        val taken = state(branchName = "agent1")
        assertTrue(taken.nameTaken)
        assertNull(taken.request)
    }

    @Test
    fun `needs a name and a folder`() {
        assertNull(state(branchName = " ").request)
        assertNull(state(folder = "").request)
    }

    @Test
    fun `checks out the branch whatever the name field says`() {
        val state = state(start = fromAgent1, checkOut = true, branchName = "agent1", nameRejected = null)

        assertEquals(NewWorktreeBranch.CheckOut("agent1"), state.request?.branch)
        assertFalse(state.nameTaken)
    }

    @Test
    fun `copies the files that worktreeinclude lists, unless unchecked, then switches when asked`() {
        val include = WorktreeIncludeState.Found(WorktreeInclude("/dev/leaf/.worktreeinclude", listOf(".env")))

        val copying = state(include = include, switchToNew = true)
        val notCopying = state(include = include, copyFiles = false, switchToNew = false)

        assertEquals(
            listOf(
                WorktreeStep.CopyFiles("/dev/leaf", "/dev/leaf-fix", listOf(".env")),
                WorktreeStep.SwitchTab("/dev/leaf-fix"),
            ),
            copying.steps.drop(1),
        )
        assertEquals(1, notCopying.steps.size)
    }

    @Test
    fun `needs a full path to a folder that's free or empty`() {
        val empty = File(tempDir, "empty").apply { mkdirs() }
        val taken = File(tempDir, "taken").apply { mkdirs(); resolve("file").writeText("x") }
        val file = File(tempDir, "file").apply { writeText("x") }

        assertNull(folderProblemOf(File(tempDir, "new").path))
        assertNull(folderProblemOf(empty.path))
        assertEquals(NewWorktreeFolderProblem.TAKEN, folderProblemOf(taken.path))
        assertEquals(NewWorktreeFolderProblem.TAKEN, folderProblemOf(file.path))
        assertEquals(NewWorktreeFolderProblem.NOT_ABSOLUTE, folderProblemOf("leaf-fix"))
        assertNull(state(folder = taken.path).request)
    }

    @Test
    fun `writes the home folder as a tilde, and reads it back`() {
        assertEquals("~/dev/leaf-fix", abbreviateHome("/Users/me/dev/leaf-fix", "/Users/me"))
        assertEquals("/Users/meow/leaf-fix", abbreviateHome("/Users/meow/leaf-fix", "/Users/me"))
        assertEquals("/Users/me/dev/leaf-fix", expandHome("~/dev/leaf-fix", "/Users/me"))
        assertEquals("~other/leaf", expandHome("~other/leaf", "/Users/me"))

        val home = System.getProperty("user.home")
        assertEquals("$home/leaf-fix", state(folder = " ~/leaf-fix ").request?.folder)
    }

    @Test
    fun `has nothing to create before the repository is read`() {
        assertNull(NewWorktreeState().request)
        assertTrue(NewWorktreeState().steps.isEmpty())
    }

    private fun state(
        start: NewWorktreeStartPoint = fromMain,
        checkOut: Boolean = false,
        branchName: String = "fix",
        nameRejected: Boolean? = false,
        folder: String = "/dev/leaf-fix",
        include: WorktreeIncludeState = WorktreeIncludeState.Missing,
        copyFiles: Boolean = true,
        switchToNew: Boolean = false,
    ) = NewWorktreeState(
        context = context,
        startPoint = start,
        checkOut = checkOut,
        branchName = branchName,
        nameRejected = nameRejected,
        folder = folder,
        include = include,
        copyFiles = copyFiles,
        switchToNew = switchToNew,
    )
}
