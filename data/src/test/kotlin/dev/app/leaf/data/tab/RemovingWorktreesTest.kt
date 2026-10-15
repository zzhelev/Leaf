// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.models.BranchMerged
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRemoval
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.worktrees.RemoveWorktreeOptions
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.lockWorktreeSteps
import dev.app.leaf.domain.worktrees.pruneWorktreesSteps
import dev.app.leaf.domain.worktrees.removeSteps
import dev.app.leaf.domain.worktrees.unlockWorktreeSteps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * A tab that removes, discards, locks and prunes the worktrees of a repository made with the git CLI: main, and agent
 * next to it, on a branch with a commit that main doesn't have. The tab runs the steps that the dialogs show.
 */
@DisabledOnOs(OS.WINDOWS)
class RemovingWorktreesTest {
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

    private val main by lazy { File(tempDir, "main") }
    private val agent by lazy { File(tempDir, "agent") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init")
        File(main, "README.md").writeText("readme\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")
        git.run(main, "worktree", "add", agent.path, "-b", "agent")
        File(agent, "agent.txt").writeText("agent\n")
        git.run(agent, "add", ".")
        git.run(agent, "commit", "-m", "Agent's work")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads what removing a worktree would lose`(): Unit = runBlocking {
        open(main)
        File(agent, "draft.txt").writeText("draft\n")

        val removal = removal(agent)

        assertEquals("agent", removal.branch)
        assertEquals("refs/heads/main", removal.baseBranch)
        assertEquals(BranchMerged(isMerged = false, commitsNotInBase = 1), removal.branchMerged)
        assertEquals(1, removal.status?.untracked)
        assertTrue(removal.hasUncommittedChanges)
        assertEquals(main.canonicalPath, File(removal.mainWorktree.path).canonicalPath)
        // draft.txt was written just now
        val lastChange = removal.lastChange
        assertNotNull(lastChange)
        assertTrue(System.currentTimeMillis() - lastChange!! < 60_000, "changed $lastChange")
    }

    @Test
    fun `never offers to delete the base branch`(): Unit = runBlocking {
        open(agent)

        val removal = removal(main)

        assertNull(removal.branch)
        assertNull(removal.branchMerged)
    }

    @Test
    fun `removes a clean worktree and deletes its branch`(): Unit = runBlocking {
        open(main)
        val removal = removal(agent)

        run(TaskType.RemoveWorktree, removal.removeSteps(RemoveWorktreeOptions(deleteBranch = true)))

        assertSuccess(TaskType.RemoveWorktree)
        assertFalse(agent.exists())
        assertEquals(listOf(main.canonicalPath), gitWorktreeList())
        assertEquals("", git.run(main, "branch", "--list", "agent"))
        tab.awaitLoaded(tab.repositoryData.worktrees) { it.worktrees.size == 1 }
    }

    @Test
    fun `git refuses a worktree with changes unless it's forced`(): Unit = runBlocking {
        open(main)
        File(agent, "draft.txt").writeText("draft\n")
        val removal = removal(agent)

        run(TaskType.RemoveWorktree, removal.removeSteps(RemoveWorktreeOptions()))

        val failure = tab.repositoryState.completedTasks.first().last() as CompletedTask.Failure
        assertEquals(TaskType.RemoveWorktree, failure.taskType)
        val error = assertInstanceOf(GitCliError.CommandFailed::class.java, failure.reason)
        assertTrue("contains modified or untracked files" in error.stderr, error.stderr)
        assertTrue(agent.isDirectory)

        run(TaskType.DiscardWorktree, removal.removeSteps(RemoveWorktreeOptions(force = true, deleteBranch = true)))

        assertSuccess(TaskType.DiscardWorktree)
        assertFalse(agent.exists())
        assertEquals("", git.run(main, "branch", "--list", "agent"))
    }

    @Test
    fun `removing the tab's own worktree switches the tab to the main worktree first`(): Unit = runBlocking {
        open(agent)
        val removal = removal(agent)
        val steps = removal.removeSteps(RemoveWorktreeOptions())

        assertTrue(removal.worktree.isCurrent)
        assertEquals(WorktreeStep.SwitchTab(removal.mainWorktree.path), steps.first())

        run(TaskType.RemoveWorktree, steps)

        assertSuccess(TaskType.RemoveWorktree)
        assertShows(main)
        assertFalse(agent.exists())
        tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.name == "refs/heads/main" }
    }

    @Test
    fun `a locked worktree is unlocked, then removed`(): Unit = runBlocking {
        git.run(main, "worktree", "lock", "--reason", "agent running", agent.path)
        open(main)
        val removal = removal(agent)
        assertEquals("agent running", removal.worktree.locked)

        run(TaskType.RemoveWorktree, removal.removeSteps(RemoveWorktreeOptions(overrideLock = true)))

        assertSuccess(TaskType.RemoveWorktree)
        assertEquals(listOf(main.canonicalPath), gitWorktreeList())
    }

    @Test
    fun `locks with a reason and unlocks`(): Unit = runBlocking {
        open(main)
        val worktree = worktree(agent)
        val mainWorktree = worktree(main)

        run(TaskType.LockWorktree, lockWorktreeSteps(worktree, mainWorktree, "on a USB drive"))

        assertSuccess(TaskType.LockWorktree)
        assertTrue("locked on a USB drive" in git.run(main, "worktree", "list", "--porcelain"))
        tab.awaitLoaded(tab.repositoryData.worktrees) { list ->
            list.worktrees.single { !it.worktree.isMain }.worktree.locked == "on a USB drive"
        }

        run(TaskType.UnlockWorktree, unlockWorktreeSteps(worktree, mainWorktree))

        assertSuccess(TaskType.UnlockWorktree)
        assertFalse("locked" in git.run(main, "worktree", "list", "--porcelain"))
    }

    @Test
    fun `prunes a worktree whose folder is missing, and keeps its branch`(): Unit = runBlocking {
        agent.deleteRecursively()
        open(main)

        run(TaskType.PruneWorktrees, pruneWorktreesSteps(worktree(main)))

        assertSuccess(TaskType.PruneWorktrees)
        assertEquals(listOf(main.canonicalPath), gitWorktreeList())
        assertTrue("agent" in git.run(main, "branch", "--list", "agent"))
    }

    @Test
    fun `a tab follows its worktree's removal to the main worktree`(): Unit = runBlocking {
        open(agent)
        val follower = tab.scope.launch { tab.followRemovedWorktree() }

        // Another tab, a terminal or an agent removes it. The file watcher would then refresh the list.
        git.run(main, "worktree", "remove", agent.path)
        tab.refreshData(DataToRefresh.WORKTREES)

        awaitShowsMain()
        assertSuccess(TaskType.FollowRemovedWorktree)
        follower.cancel()
    }

    @Test
    fun `a tab follows to the main worktree when its worktree's folder is deleted`(): Unit = runBlocking {
        open(agent)
        val follower = tab.scope.launch { tab.followRemovedWorktree() }

        agent.deleteRecursively()
        tab.refreshData(DataToRefresh.WORKTREES)

        awaitShowsMain()
        follower.cancel()
    }

    @Test
    fun `a tab on a worktree that's still there stays on it`(): Unit = runBlocking {
        open(agent)
        val follower = tab.scope.launch { tab.followRemovedWorktree() }

        tab.refreshData(DataToRefresh.WORKTREES)
        tab.awaitLoaded(tab.repositoryData.worktrees)

        assertShows(agent)
        follower.cancel()
    }

    private suspend fun open(worktree: File) {
        tab.openRepository(worktree.path)
        tab.awaitLoaded(tab.repositoryData.worktrees) { list -> list.worktrees.size == 2 || !agent.exists() }
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun worktree(folder: File): Worktree {
        val list = tab.awaitLoaded(tab.repositoryData.worktrees)

        return list.worktrees.map { it.worktree }.single { File(it.path).canonicalPath == folder.canonicalPath }
    }

    private suspend fun removal(folder: File): WorktreeRemoval {
        val result = tab.getWorktreeRemoval(worktree(folder))

        return (result as Either.Ok).value
    }

    private suspend fun run(taskType: TaskType, steps: List<WorktreeStep>) {
        tab.runWorktreeSteps(taskType, steps).join()
    }

    private suspend fun assertSuccess(taskType: TaskType) {
        // A task that the tab started itself, such as following a removed worktree, may still be running
        val task = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()

        assertEquals(taskType, task.taskType, task.toString())
        assertInstanceOf(CompletedTask.Success::class.java, task, task.toString())
    }

    /** The folders that `git worktree list` lists, which has no stale entries once a worktree is gone. */
    private fun gitWorktreeList(): List<String> = git.run(main, "worktree", "list", "--porcelain")
        .lines()
        .filter { it.startsWith("worktree ") }
        .map { File(it.removePrefix("worktree ")).canonicalPath }

    /** Checks that the tab shows [worktree]: it has its git dir open, whichever path git or the tab gave it. */
    private fun assertShows(worktree: File) {
        val state = tab.repositoryData.repositorySelectionState.value
        val openPath = assertInstanceOf(RepositorySelectionState.Open::class.java, state).path

        assertEquals(gitDir(worktree), File(openPath).canonicalPath)
    }

    /** Waits until the tab shows the main worktree, which it switches to by itself. */
    private suspend fun awaitShowsMain() {
        val mainGitDir = gitDir(main)

        withTimeout(10.seconds) {
            tab.repositoryData.repositorySelectionState.first { state ->
                state is RepositorySelectionState.Open && File(state.path).canonicalPath == mainGitDir
            }
        }
    }

    private fun gitDir(worktree: File): String =
        File(git.run(worktree, "rev-parse", "--absolute-git-dir").trim()).canonicalPath
}
