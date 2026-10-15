// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRebase
import dev.app.leaf.domain.models.WorktreeRebaseRefusal
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.rebaseSteps
import dev.app.leaf.domain.worktrees.refusal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseResult
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
import kotlin.time.Duration.Companion.seconds

/**
 * A tab that rebases a worktree's branch onto main, in a repository made with the git CLI: main, and agent next to it,
 * on the branch agent1 with two commits that main doesn't have. The tab runs the steps that the rebase dialog shows,
 * and goes on with a rebase that stopped as the status pane's buttons do.
 */
@DisabledOnOs(OS.WINDOWS)
class RebasingWorktreesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tab by lazy {
        // The user's editor, from the login shell, which git would open to continue a rebase if Leaf didn't set its own
        val shellVariables = mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_EDITOR" to "false",
        )

        TestTab(testJGit(), testGitCli(shellVariables))
    }

    private val main by lazy { File(tempDir, "main") }
    private val agent by lazy { File(tempDir, "agent") }
    private val agentGitDir by lazy { File(main, ".git/worktrees/agent") }

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
        git.run(agent, "commit", "-am", "Add b\n\nBody of b.")
        File(agent, "new.txt").writeText("new\n")
        git.run(agent, "add", ".")
        git.run(agent, "commit", "-m", "Add a new file")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `reads what rebasing a worktree's branch onto main needs`(): Unit = runBlocking {
        changeReadmeOnMain()
        open(main)

        val rebase = rebase(agent)

        assertEquals("refs/heads/agent1", rebase.branch)
        assertEquals("refs/heads/main", rebase.base)
        assertEquals("main", rebase.baseArgument)
        assertEquals(2, rebase.commitCount)
        assertEquals(1, rebase.baseCommitCount)
        assertFalse(rebase.status!!.isDirty)
        assertNull(rebase.refusal())
        assertEquals(
            listOf(
                WorktreeStep.Git(rebase.worktree.path, listOf("rebase", "--no-autostash", "main"), true),
                WorktreeStep.SwitchTab(rebase.worktree.path, onlyIfStoppedAtConflicts = true),
            ),
            rebase.rebaseSteps(),
        )
    }

    @Test
    fun `rebases in the branch's worktree, and the tab stays where it was`(): Unit = runBlocking {
        changeReadmeOnMain()
        open(main)

        run(rebase(agent))

        assertTask(TaskType.RebaseWorktree, stoppedAtConflicts = false)
        assertShows(main)
        assertEquals(sha("main"), sha("agent1~2"))
        assertEquals(listOf("Add a new file", "Add b"), subjects("main..agent1"))
    }

    @Test
    fun `a rebase that stops at conflicts switches the tab to the branch's worktree`(): Unit = runBlocking {
        changeFOnMain()
        open(main)

        run(rebase(agent))

        assertTask(TaskType.RebaseWorktree, stoppedAtConflicts = true)
        assertShows(agent)
        assertEquals(listOf("UU f.txt"), gitStatus(agent))
        assertTrue(tab.awaitLoaded(tab.repositoryData.repositoryState) { it.isRebasing }.isRebasing)
    }

    @Test
    fun `a rebase that stops in the tab's own worktree stays there`(): Unit = runBlocking {
        changeFOnMain()
        open(agent)

        run(rebase(agent))

        assertTask(TaskType.RebaseWorktree, stoppedAtConflicts = true)
        assertShows(agent)
    }

    @Test
    fun `Continue goes on with git's rebase, and keeps the commit's message as it was`(): Unit = runBlocking {
        val message = message("agent1~1")
        changeFOnMain()
        open(main)
        run(rebase(agent))
        resolveF()

        continueRebase()

        assertTask(TaskType.ContinueRebase, stoppedAtConflicts = false)
        assertEquals(listOf("Add a new file", "Add b"), subjects("main..agent1"))
        // JGit's continue committed git's message file with its "# Conflicts:" lines
        assertEquals(message, message("agent1~1"))
        assertFalse(File(agentGitDir, "rebase-merge").exists())
        assertFalse(File(agentGitDir, "MERGE_MSG").exists())
        assertEquals("agent1", git.run(agent, "branch", "--show-current").trim())
    }

    @Test
    fun `Continue that stops at the next commit's conflicts says so`(): Unit = runBlocking {
        changeFOnMain()
        File(main, "new.txt").writeText("other\n")
        git.run(main, "add", "new.txt")
        git.run(main, "commit", "-m", "Add another new file")
        open(main)
        run(rebase(agent))
        resolveF()

        continueRebase()

        assertTask(TaskType.ContinueRebase, stoppedAtConflicts = true)
        assertEquals(listOf("AA new.txt"), gitStatus(agent))
    }

    @Test
    fun `Skip goes on with git's rebase without the commit`(): Unit = runBlocking {
        changeFOnMain()
        open(main)
        run(rebase(agent))

        awaitTask { tab.skipRebase() }

        assertTask(TaskType.SkipRebase, stoppedAtConflicts = false)
        assertEquals(listOf("Add a new file"), subjects("main..agent1"))
        assertFalse(File(agentGitDir, "rebase-merge").exists())
        // JGit's skip left the skipped commit's message, which a commit from a terminal would take
        assertFalse(File(agentGitDir, "MERGE_MSG").exists())
    }

    @Test
    fun `Abort puts the branch back with git`(): Unit = runBlocking {
        val before = sha("agent1")
        changeFOnMain()
        open(main)
        run(rebase(agent))

        awaitTask { tab.abortRebase() }

        assertTask(TaskType.AbortRebase, stoppedAtConflicts = false)
        assertEquals(before, sha("agent1"))
        assertEquals("agent1", git.run(agent, "branch", "--show-current").trim())
        assertEquals(emptyList<String>(), gitStatus(agent))
        assertFalse(File(agentGitDir, "rebase-merge").exists())
        assertFalse(File(agentGitDir, "MERGE_MSG").exists())
    }

    @Test
    fun `a rebase that JGit started still continues with JGit, which git can't do`(): Unit = runBlocking {
        val message = message("agent1~1")
        changeFOnMain()
        Git.open(agent).use { jgit ->
            val result = jgit.rebase().setUpstream(jgit.repository.resolve("main")).call()
            assertEquals(RebaseResult.Status.STOPPED, result.status)
        }
        open(agent)
        resolveF()

        continueRebase()

        assertTask(TaskType.ContinueRebase, stoppedAtConflicts = false)
        assertEquals(listOf("Add a new file", "Add b"), subjects("main..agent1"))
        assertEquals(message, message("agent1~1"))
    }

    @Test
    fun `refuses a worktree with changes, and git refuses changes made after the dialog checked`(): Unit =
        runBlocking {
            changeReadmeOnMain()
            open(main)
            File(agent, "f.txt").writeText("a\nb\nmine\n")
            assertEquals(WorktreeRebaseRefusal.HasChanges(1), rebase(agent).refusal())

            git.run(agent, "checkout", "--", "f.txt")
            val rebase = rebase(agent)
            assertNull(rebase.refusal())
            // An agent writes after the dialog checked, and the user's config would stash its changes
            File(agent, "f.txt").writeText("a\nb\nagent\n")
            git.run(main, "config", "rebase.autoStash", "true")

            run(rebase)

            val task = tab.repositoryState.completedTasks.first().last()
            assertInstanceOf(CompletedTask.Failure::class.java, task, task.toString())
            val error = (task as CompletedTask.Failure).reason
            assertInstanceOf(GitCliError.CommandFailed::class.java, error, error.toString())
            assertTrue((error as GitCliError.CommandFailed).stderr.contains("cannot rebase"), error.stderr)
            assertEquals("a\nb\nagent\n", File(agent, "f.txt").readText())
            assertEquals("", git.run(main, "stash", "list"))
        }

    @Test
    fun `refuses a branch that already has main's commits`(): Unit = runBlocking {
        open(main)

        assertEquals(WorktreeRebaseRefusal.UpToDate, rebase(agent).refusal())
    }

    @Test
    fun `names main by its full name when a tag has its short name`(): Unit = runBlocking {
        git.run(main, "tag", "main", "main")
        changeReadmeOnMain()
        open(main)

        val rebase = rebase(agent)
        assertEquals("refs/heads/main", rebase.baseArgument)

        run(rebase)

        assertTask(TaskType.RebaseWorktree, stoppedAtConflicts = false)
        assertEquals(sha("refs/heads/main"), sha("agent1~2"))
    }

    @Test
    fun `rebases onto a remote base by its short name`(): Unit = runBlocking {
        changeReadmeOnMain()
        git.run(main, "update-ref", "refs/remotes/origin/main", "main")
        git.run(main, "config", "-f", ".git/leaf", "worktrees.baseBranch", "refs/remotes/origin/main")
        open(main)

        val rebase = rebase(agent)
        assertEquals("refs/remotes/origin/main", rebase.base)
        assertEquals("origin/main", rebase.baseArgument)

        run(rebase)

        assertTask(TaskType.RebaseWorktree, stoppedAtConflicts = false)
        assertEquals(sha("origin/main"), sha("agent1~2"))
    }

    private fun changeReadmeOnMain() {
        File(main, "README.md").writeText("readme, changed\n")
        git.run(main, "commit", "-am", "Change the readme")
    }

    /** A change on main that conflicts with the first commit of agent1. */
    private fun changeFOnMain() {
        File(main, "f.txt").writeText("z\n")
        git.run(main, "commit", "-am", "Change f on main")
    }

    private fun resolveF() {
        File(agent, "f.txt").writeText("z\nb\n")
        git.run(agent, "add", "f.txt")
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

    private suspend fun rebase(folder: File): WorktreeRebase {
        val result = tab.getWorktreeRebase(worktree(folder))

        return (result as Either.Ok).value
    }

    private suspend fun run(rebase: WorktreeRebase) {
        tab.runWorktreeSteps(TaskType.RebaseWorktree, rebase.rebaseSteps()).join()
    }

    /** Continues as the status pane's Continue does. */
    private suspend fun continueRebase() {
        val repositoryState = tab.awaitLoaded(tab.repositoryData.repositoryState) { it.isRebasing }

        awaitTask {
            tab.continueRebase(
                message = "",
                isAmendRebaseInteractive = false,
                repositoryState = repositoryState,
                rebaseInteractiveState = RebaseInteractiveState.None,
                onIdentityRequest = { null },
            )
        }
    }

    /** Starts a task that can't be joined, and waits until it ends. */
    private suspend fun awaitTask(start: () -> Unit) {
        val before = tab.repositoryState.completedTasks.value.size

        start()

        withTimeout(30.seconds) { tab.repositoryState.completedTasks.first { it.size > before } }
    }

    private suspend fun assertTask(taskType: TaskType, stoppedAtConflicts: Boolean) {
        val task = tab.repositoryState.completedTasks.first().last()

        assertEquals(CompletedTask.Success(task.date, taskType, stoppedAtConflicts), task)
    }

    private fun assertShows(worktree: File) {
        val selection = tab.repositoryData.repositorySelectionState.value as RepositorySelectionState.Open
        val gitDir = if (worktree == main) File(main, ".git") else agentGitDir

        assertEquals(gitDir.canonicalPath, File(selection.path).canonicalPath)
    }

    private fun sha(revision: String) = git.run(main, "rev-parse", revision).trim()

    private fun message(revision: String) = git.run(main, "log", "-1", "--format=%B", revision)

    private fun subjects(range: String) = git.run(main, "log", "--format=%s", range).lines().filter { it.isNotBlank() }

    private fun gitStatus(worktree: File): List<String> =
        git.run(worktree, "status", "--porcelain").lines().filter { it.isNotBlank() }
}
