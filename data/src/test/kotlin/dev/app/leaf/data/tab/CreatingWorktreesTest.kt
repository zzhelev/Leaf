// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.models.NewWorktreeContext
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.WorktreeCreationSettings
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.worktrees.NewWorktreeBranch
import dev.app.leaf.domain.worktrees.NewWorktreeRequest
import dev.app.leaf.domain.worktrees.newWorktreeFolder
import dev.app.leaf.domain.worktrees.startPoint
import dev.app.leaf.domain.worktrees.steps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that creates worktrees of a repository made with the git CLI, as the new worktree dialog does: main, which
 * ignores `.env`, and a branch `agent1` that no worktree uses.
 */
@DisabledOnOs(OS.WINDOWS)
class CreatingWorktreesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tab by lazy {
        TestTab(
            testJGit(),
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }

    private val main by lazy { File(tempDir, "leaf") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init")
        File(main, ".gitignore").writeText(".env\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")
        git.run(main, "branch", "agent1")
        File(main, ".env").writeText("SECRET=1\n")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads the main worktree, the base branch and the branches for the dialog`(): Unit = runBlocking {
        open()

        val context = context()

        assertEquals(main.canonicalPath, File(context.mainWorktree.path).canonicalPath)
        assertEquals("refs/heads/main", context.baseBranch)
        assertEquals(setOf("refs/heads/main", "refs/heads/agent1"), context.localBranches)
        assertEquals(setOf("refs/heads/main"), context.branchesInUse)
        assertEquals(WorktreeCreationSettings(), context.settings)
    }

    @Test
    fun `creates a branch from the base, copies the ignored files and switches the tab`(): Unit = runBlocking {
        open()
        val context = context()
        val folder = newWorktreeFolder(context.mainWorktree, null, "fix/auth")
        val start = NewWorktreeStart.Base.startPoint(context)

        create(
            NewWorktreeRequest(
                mainWorktree = context.mainWorktree,
                startPoint = start.startPoint,
                tracking = start.tracking,
                branch = NewWorktreeBranch.Create("fix/auth"),
                folder = folder,
                copyFiles = listOf(".env"),
                switchTab = true,
            ),
            WorktreeCreationSettings(folder = null, switchToNew = true),
        )

        assertSuccess()
        assertEquals("leaf-fix-auth", File(folder).name)
        assertEquals("SECRET=1\n", File(folder, ".env").readText())
        assertEquals("fix/auth", git.run(File(folder), "branch", "--show-current").trim())
        assertShows(File(folder))
        tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.name == "refs/heads/fix/auth" }
    }

    @Test
    fun `checks out a branch in a chosen folder, keeps the tab where it is, and remembers the folder`(): Unit =
        runBlocking {
            open()
            val context = context()
            val agents = File(tempDir, "agents")
            val folder = newWorktreeFolder(context.mainWorktree, agents.path, "agent1")
            val start = NewWorktreeStart.LocalBranch(context.branch("refs/heads/agent1")).startPoint(context)

            create(
                NewWorktreeRequest(
                    mainWorktree = context.mainWorktree,
                    startPoint = start.startPoint,
                    tracking = start.tracking,
                    branch = NewWorktreeBranch.CheckOut(start.branchToCheckOut!!),
                    folder = folder,
                    copyFiles = emptyList(),
                    switchTab = false,
                ),
                WorktreeCreationSettings(folder = agents.path, switchToNew = false),
            )

            assertSuccess()
            assertEquals("agent1", git.run(File(folder), "branch", "--show-current").trim())
            assertFalse(File(folder, ".env").exists())
            assertShows(main)
            tab.awaitLoaded(tab.repositoryData.worktrees) { it.worktrees.size == 2 }
            assertEquals(WorktreeCreationSettings(agents.path, switchToNew = false), context().settings)
        }

    @Test
    fun `git refuses a folder that isn't empty, and nothing is copied or switched`(): Unit = runBlocking {
        open()
        val context = context()
        val folder = File(tempDir, "taken").apply { mkdirs(); resolve("file.txt").writeText("x\n") }

        create(
            NewWorktreeRequest(
                mainWorktree = context.mainWorktree,
                startPoint = "main",
                tracking = NewWorktreeStart.Base.startPoint(context).tracking,
                branch = NewWorktreeBranch.Create("taken"),
                folder = folder.path,
                copyFiles = listOf(".env"),
                switchTab = true,
            ),
            WorktreeCreationSettings(),
        )

        val failure = tab.repositoryState.completedTasks.first().last() as CompletedTask.Failure
        assertEquals(TaskType.CreateWorktree, failure.taskType)
        val error = assertInstanceOf(GitCliError.CommandFailed::class.java, failure.reason)
        assertTrue("already exists" in error.stderr, error.stderr)
        assertFalse(File(folder, ".env").exists())
        assertShows(main)
    }

    private suspend fun open() {
        tab.openRepository(main.path)
        tab.awaitLoaded(tab.repositoryData.worktrees)
        tab.awaitLoaded(tab.repositoryData.localBranches) { it.size == 2 }
        tab.awaitLoaded(tab.repositoryData.currentBranch)
    }

    private suspend fun context(): NewWorktreeContext = (tab.getNewWorktreeContext() as Either.Ok).value

    private suspend fun create(request: NewWorktreeRequest, settings: WorktreeCreationSettings) {
        tab.createWorktree(request.steps(), settings).join()
    }

    private suspend fun assertSuccess() {
        val task = tab.repositoryState.completedTasks.first().last()

        assertEquals(TaskType.CreateWorktree, task.taskType, task.toString())
        assertInstanceOf(CompletedTask.Success::class.java, task, task.toString())
    }

    /** Checks that the tab shows [worktree]: it has its git dir open, whichever path git or the tab gave it. */
    private fun assertShows(worktree: File) {
        val state = tab.repositoryData.repositorySelectionState.value
        val openPath = assertInstanceOf(RepositorySelectionState.Open::class.java, state).path
        val gitDir = File(git.run(worktree, "rev-parse", "--absolute-git-dir").trim()).canonicalPath

        assertEquals(gitDir, File(openPath).canonicalPath)
    }

    private fun NewWorktreeContext.branch(name: String) = dev.app.leaf.domain.models.Branch(
        hash = git.run(main, "rev-parse", name).trim(),
        name = name,
        isLocal = true,
    )
}
