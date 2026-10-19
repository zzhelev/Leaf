// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Finishing a merge, a cherry-pick, a revert or a rebase with files around that aren't staged (Gitnuro#120): the
 * status pane's Merge, Commit and Continue are enabled then (`FinishBlockers`), and these are the use cases they run.
 * The repository: main has base, A and B on conflict.txt, feature has one commit on it, so merging or cherry-picking
 * feature and reverting A all stop at a conflict. tracked.txt is a tracked file the tests change, build.log is an
 * untracked file.
 */
@DisabledOnOs(OS.WINDOWS)
class FinishingWithUnstagedFilesTest {
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
    private val repository by lazy { File(tempDir, "repository") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        // The tab's git commits as this identity: without one, git makes one up on macOS, and refuses on Linux
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        repository.mkdirs()
        git.run(repository, "init", "-b", "main")
        write("conflict.txt" to "base\n", "tracked.txt" to "t1\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "switch", "-c", "feature")
        write("conflict.txt" to "feature\n")
        git.run(repository, "commit", "-am", "Feature")
        git.run(repository, "switch", "main")
        write("conflict.txt" to "a\n")
        git.run(repository, "commit", "-am", "A")
        write("conflict.txt" to "b\n")
        git.run(repository, "commit", "-am", "B")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    // --- Merge

    @Test
    fun `a merge is committed with an untracked file and a changed tracked file around, which stay`(): Unit =
        runBlocking {
            git.runFailing(repository, "merge", "feature")
            resolve()
            write("build.log" to "output\n", "tracked.txt" to "edited\n")
            open()

            val commit = tab.doCommit("Merge branch 'feature'", amend = false, author = null, isMergeMessage = true).await()

            assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
            assertEquals(2, parents())
            assertEquals("resolved\n", git.run(repository, "show", "HEAD:conflict.txt"))
            assertEquals(listOf(" M tracked.txt", "?? build.log"), status())
        }

    @Test
    fun `a merge resolved to our side has nothing staged, and is committed with two parents`(): Unit = runBlocking {
        git.runFailing(repository, "merge", "feature")
        // Gitnuro#120: every conflict taken from our side, a merge tool's leftover file in the folder
        git.run(repository, "checkout", "--ours", "conflict.txt")
        git.run(repository, "add", "conflict.txt")
        write("conflict.txt.orig" to "leftover\n")
        assertEquals(emptyList<String>(), git.run(repository, "diff", "--cached", "--name-only").lines().filter { it.isNotBlank() })
        open()

        val commit = tab.doCommit("Merge branch 'feature'", amend = false, author = null, isMergeMessage = true).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals(2, parents())
        assertEquals(git.run(repository, "rev-parse", "HEAD~1^{tree}").trim(), git.run(repository, "rev-parse", "HEAD^{tree}").trim())
        assertEquals(listOf("?? conflict.txt.orig"), status())
    }

    // --- Cherry-pick and revert

    @Test
    fun `a cherry-pick is committed with files around that aren't staged`(): Unit = runBlocking {
        git.runFailing(repository, "cherry-pick", "feature")
        resolve()
        write("build.log" to "output\n", "tracked.txt" to "edited\n")
        open()

        val commit = tab.doCommit("Feature", amend = false, author = null, isMergeMessage = true).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals(1, parents())
        assertEquals("Feature", subject())
        assertEquals(listOf(" M tracked.txt", "?? build.log"), status())
    }

    @Test
    fun `a revert is committed with files around that aren't staged`(): Unit = runBlocking {
        git.runFailing(repository, "revert", "--no-edit", "HEAD~1")
        resolve()
        write("build.log" to "output\n", "tracked.txt" to "edited\n")
        open()

        val commit = tab.doCommit("Revert \"A\"", amend = false, author = null, isMergeMessage = true).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals("Revert \"A\"", subject())
        assertEquals(listOf(" M tracked.txt", "?? build.log"), status())
    }

    @Test
    fun `a cherry-pick or a revert with nothing staged is refused, which is why Commit is disabled then`(): Unit =
        runBlocking {
            // The conflict resolved to what HEAD has: nothing to commit. git refuses an empty commit unless allowed.
            git.runFailing(repository, "cherry-pick", "feature")
            git.run(repository, "checkout", "--ours", "conflict.txt")
            git.run(repository, "add", "conflict.txt")
            write("build.log" to "output\n")
            open()
            val head = head()

            val cherryPick = tab.doCommit("Feature", amend = false, author = null, isMergeMessage = true).await()

            assertInstanceOf(Either.Err::class.java, cherryPick, cherryPick.toString())
            assertEquals(head, head())

            git.run(repository, "cherry-pick", "--abort")
            git.runFailing(repository, "revert", "--no-edit", "HEAD~1")
            git.run(repository, "checkout", "--ours", "conflict.txt")
            git.run(repository, "add", "conflict.txt")

            val revert = tab.doCommit("Revert \"A\"", amend = false, author = null, isMergeMessage = true).await()

            assertInstanceOf(Either.Err::class.java, revert, revert.toString())
            assertEquals(head, head())
        }

    // --- Rebase

    @Test
    fun `Continue goes on with git's rebase and an untracked file around`(): Unit = runBlocking {
        git.run(repository, "switch", "feature")
        git.runFailing(repository, "rebase", "main")
        resolve()
        write("build.log" to "output\n")
        open()

        continueRebase()

        assertTask(TaskType.ContinueRebase)
        assertFalse(File(repository, ".git/rebase-merge").exists())
        assertEquals(listOf("Feature", "B", "A", "Base"), subjects())
        assertEquals(listOf("?? build.log"), status())
    }

    @Test
    fun `Continue goes on with JGit's rebase and an untracked file around`(): Unit = runBlocking {
        git.run(repository, "switch", "feature")
        Git.open(repository).use { jgit ->
            val result = jgit.rebase().setUpstream(jgit.repository.resolve("main")).call()
            assertEquals(RebaseResult.Status.STOPPED, result.status)
        }
        resolve()
        write("build.log" to "output\n")
        open()

        continueRebase()

        assertTask(TaskType.ContinueRebase)
        assertFalse(File(repository, ".git/rebase-merge").exists())
        assertEquals(listOf("Feature", "B", "A", "Base"), subjects())
        assertEquals(listOf("?? build.log"), status())
    }

    @Test
    fun `Skip goes on with git's rebase and an untracked file around, which stays`(): Unit = runBlocking {
        git.run(repository, "switch", "feature")
        git.runFailing(repository, "rebase", "main")
        write("build.log" to "output\n")
        open()

        awaitTask { tab.skipRebase() }

        assertTask(TaskType.SkipRebase)
        assertEquals(listOf("B", "A", "Base"), subjects())
        assertEquals(listOf("?? build.log"), status())
    }

    // --- Helpers

    private suspend fun open() {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)
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

    private fun assertTask(taskType: TaskType) {
        val task = tab.repositoryState.completedTasks.value.last()

        assertEquals(CompletedTask.Success(task.date, taskType, stoppedAtConflicts = false), task)
    }

    private fun resolve() {
        write("conflict.txt" to "resolved\n")
        git.run(repository, "add", "conflict.txt")
    }

    private fun write(vararg files: Pair<String, String>) {
        for ((file, content) in files) {
            File(repository, file).writeText(content)
        }
    }

    private fun parents() = git.run(repository, "rev-list", "--parents", "-n1", "HEAD").trim().split(' ').size - 1

    private fun subject() = git.run(repository, "log", "-1", "--format=%s").trim()

    private fun subjects() = git.run(repository, "log", "--format=%s").lines().filter { it.isNotBlank() }

    private fun head() = git.run(repository, "rev-parse", "HEAD").trim()

    private fun status() = git.run(repository, "status", "--short").lines().filter { it.isNotBlank() }
}
