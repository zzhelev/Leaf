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
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeMerge
import dev.app.leaf.domain.models.WorktreeMergeKind
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.worktrees.isSquashMergeInProgress
import dev.app.leaf.domain.worktrees.mergeSteps
import dev.app.leaf.domain.worktrees.squashMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that squash-merges and merges a worktree's branch into main, in a repository made with the git CLI: main, and
 * agent next to it, on the branch agent1 with two commits that main doesn't have. The tab runs the steps that the
 * merge dialog shows, and commits as the commit field does.
 */
@DisabledOnOs(OS.WINDOWS)
class MergingWorktreesTest {
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
    private val mainGitDir by lazy { File(main, ".git") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        // The tab's git commits as this identity: without one, git makes one up on macOS, and refuses on Linux
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        main.mkdirs()
        git.run(main, "init", "-b", "main")
        File(main, "README.md").writeText("readme\n")
        File(main, "f.txt").writeText("a\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")
        git.run(main, "worktree", "add", agent.path, "-b", "agent1")
        File(agent, "f.txt").writeText("a\nb\n")
        git.run(agent, "commit", "-am", "Add b")
        File(agent, "new.txt").writeText("new\n")
        git.run(agent, "add", ".")
        git.run(agent, "commit", "-m", "Add a new file\n\nWith a body.")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads what merging a worktree's branch into main needs`(): Unit = runBlocking {
        open(agent)

        val merge = merge(agent)

        assertEquals("refs/heads/agent1", merge.branch)
        assertEquals("agent1", merge.branchArgument)
        assertEquals("refs/heads/main", merge.target)
        assertEquals(main.canonicalPath, File(merge.targetWorktree!!.path).canonicalPath)
        assertEquals(agent.canonicalPath, File(merge.tabWorktree.path).canonicalPath)
        assertEquals(2, merge.commitCount)
        assertEquals(listOf("Add b", "Add a new file"), merge.commits.map { it.shortMessage })
        assertEquals(BranchMerged(isMerged = false, commitsNotInBase = 2), merge.branchMerged)
        assertFalse(merge.targetStatus!!.isDirty)
    }

    @Test
    fun `squash-merges from the branch's tab in main's worktree, with Leaf's message for the commit`(): Unit =
        runBlocking {
            open(agent)

            run(merge(agent), WorktreeMergeKind.SQUASH)

            assertTask(TaskType.SquashMergeWorktree, stoppedAtConflicts = false)
            assertShows(main)
            assertEquals(listOf("M  f.txt", "A  new.txt"), gitStatus(main))
            assertEquals("agent1\n\n- Add b\n- Add a new file", File(mainGitDir, "SQUASH_MSG").readText())

            // What the commit field and the banner read
            val persisted = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.squashMessage != null }
            val status = tab.awaitLoaded(tab.repositoryData.status)
            assertTrue(isSquashMergeInProgress(RepositoryState.SAFE, persisted.squashMessage, status))
        }

    @Test
    fun `Leaf's commit ends the squash merge, as git's does`(): Unit = runBlocking {
        open(main)
        run(merge(agent), WorktreeMergeKind.SQUASH)

        val commit = tab.doCommit("agent1\n\n- Add b\n- Add a new file", amend = false, author = null).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals(emptyList<String>(), gitStatus(main))
        assertFalse(File(mainGitDir, "SQUASH_MSG").exists())
        assertEquals("agent1\n\n- Add b\n- Add a new file\n", git.run(main, "log", "-1", "--format=%B"))
        assertEquals(1, git.run(main, "log", "-1", "--format=%P").trim().split(" ").size)
    }

    @Test
    fun `a squash merge that stops at conflicts keeps Leaf's message, and is aborted with git reset --merge`(): Unit =
        runBlocking {
            File(main, "f.txt").writeText("z\n")
            git.run(main, "commit", "-am", "Change f on main")
            open(main)

            run(merge(agent), WorktreeMergeKind.SQUASH)

            assertTask(TaskType.SquashMergeWorktree, stoppedAtConflicts = true)
            assertEquals(listOf("UU f.txt", "A  new.txt"), gitStatus(main))
            assertEquals("agent1\n\n- Add b\n- Add a new file", File(mainGitDir, "SQUASH_MSG").readText())

            tab.abortSquashMerge().join()

            assertTask(TaskType.AbortSquashMerge, stoppedAtConflicts = false)
            assertEquals(emptyList<String>(), gitStatus(main))
            assertFalse(File(mainGitDir, "SQUASH_MSG").exists())
            assertFalse(File(mainGitDir, "MERGE_MSG").exists())
        }

    @Test
    fun `Leaf's commit of a squash merge that had conflicts leaves no message for the next commit`(): Unit =
        runBlocking {
            File(main, "f.txt").writeText("z\n")
            git.run(main, "commit", "-am", "Change f on main")
            open(main)
            run(merge(agent), WorktreeMergeKind.SQUASH)
            // git writes MERGE_MSG too, which `git commit` would take as the next commit's message
            assertTrue(File(mainGitDir, "MERGE_MSG").exists())

            File(main, "f.txt").writeText("z\nb\n")
            git.run(main, "add", "f.txt")
            val commit = tab.doCommit("Squashed agent1", amend = false, author = null).await()

            assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
            for (name in listOf("SQUASH_MSG", "MERGE_MSG", "MERGE_MODE", "AUTO_MERGE")) {
                assertFalse(File(mainGitDir, name).exists(), name)
            }
        }

    @Test
    fun `merges without a fast-forward, and stops before the commit with git's message`(): Unit = runBlocking {
        open(main)

        run(merge(agent), WorktreeMergeKind.MERGE)

        assertTask(TaskType.MergeWorktree, stoppedAtConflicts = false)
        assertEquals("Merge branch 'agent1'", File(mainGitDir, "MERGE_MSG").readText().lineSequence().first())
        assertEquals(git.run(main, "rev-parse", "agent1").trim(), File(mainGitDir, "MERGE_HEAD").readText().trim())
        // The task's refresh runs after it ends
        val repositoryState = tab.awaitLoaded(tab.repositoryData.repositoryState) { it != RepositoryState.SAFE }
        assertEquals(RepositoryState.MERGING_RESOLVED, repositoryState)

        val commit = tab.doCommit("Merge branch 'agent1'", amend = false, author = null).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals(2, git.run(main, "log", "-1", "--format=%P").trim().split(" ").size)
        assertFalse(File(mainGitDir, "MERGE_MODE").exists())
    }

    @Test
    fun `a merge that git refuses fails with git's message, and writes no squash message`(): Unit = runBlocking {
        open(main)
        File(main, "f.txt").writeText("local\n")

        run(merge(agent), WorktreeMergeKind.SQUASH)

        val task = tab.repositoryState.completedTasks.first().last()
        assertInstanceOf(CompletedTask.Failure::class.java, task, task.toString())
        val error = (task as CompletedTask.Failure).reason
        assertInstanceOf(GitCliError.CommandFailed::class.java, error, error.toString())
        assertTrue((error as GitCliError.CommandFailed).stderr.contains("would be overwritten by merge"), error.stderr)
        assertFalse(File(mainGitDir, "SQUASH_MSG").exists())
    }

    @Test
    fun `checks main out in the tab's worktree when no worktree has it`(): Unit = runBlocking {
        git.run(main, "switch", "-c", "other")
        open(main)

        val merge = merge(agent)
        assertNull(merge.targetWorktree)

        run(merge, WorktreeMergeKind.SQUASH)

        assertTask(TaskType.SquashMergeWorktree, stoppedAtConflicts = false)
        assertEquals("main", git.run(main, "branch", "--show-current").trim())
        assertEquals(listOf("M  f.txt", "A  new.txt"), gitStatus(main))
    }

    @Test
    fun `names the branch by its full name when a tag has its short name`(): Unit = runBlocking {
        git.run(main, "tag", "agent1", "main")
        open(main)

        val merge = merge(agent)
        assertEquals("refs/heads/agent1", merge.branchArgument)

        run(merge, WorktreeMergeKind.MERGE)

        val mergeHead = File(mainGitDir, "MERGE_HEAD").readText().trim()
        assertEquals(git.run(main, "rev-parse", "refs/heads/agent1").trim(), mergeHead)
    }

    private suspend fun open(worktree: File) {
        tab.openRepository(worktree.path)
        tab.awaitLoaded(tab.repositoryData.worktrees) { list -> list.worktrees.size == 2 }
        tab.awaitLoaded(tab.repositoryData.localBranches)
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun worktree(folder: File): Worktree {
        val list = tab.awaitLoaded(tab.repositoryData.worktrees)

        return list.worktrees.map { it.worktree }.single { File(it.path).canonicalPath == folder.canonicalPath }
    }

    private suspend fun merge(folder: File): WorktreeMerge {
        val result = tab.getWorktreeMerge(worktree(folder))

        return (result as Either.Ok).value
    }

    private suspend fun run(merge: WorktreeMerge, kind: WorktreeMergeKind) {
        val taskType = when (kind) {
            WorktreeMergeKind.SQUASH -> TaskType.SquashMergeWorktree
            WorktreeMergeKind.MERGE -> TaskType.MergeWorktree
        }
        val message = squashMessage(merge.branch, merge.commits, merge.commitCount)

        tab.runWorktreeSteps(taskType, merge.mergeSteps(kind, message)).join()
    }

    private suspend fun assertTask(taskType: TaskType, stoppedAtConflicts: Boolean) {
        val task = tab.repositoryState.completedTasks.first().last()

        assertEquals(CompletedTask.Success(task.date, taskType, stoppedAtConflicts), task)
    }

    private fun assertShows(worktree: File) {
        val selection = tab.repositoryData.repositorySelectionState.value as RepositorySelectionState.Open

        assertEquals(File(worktree, ".git").canonicalPath, File(selection.path).canonicalPath)
    }

    private fun gitStatus(worktree: File): List<String> =
        git.run(worktree, "status", "--porcelain").lines().filter { it.isNotBlank() }
}
