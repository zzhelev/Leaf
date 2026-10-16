// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AutostashNotice
import dev.app.leaf.domain.models.AutostashOperation
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.time.Duration.Companion.seconds

// SnapshotStashCreateCommand formats it with MessageFormat, which drops apostrophes
private const val BACKUP_MESSAGE = "Backup before merging"

/**
 * A tab that merges with the user's `merge.autoStash`, and commits and aborts as Leaf does: git sets local changes
 * aside, and they come back as with `git commit` and `git merge --abort`. The repository: main and feature both
 * changed conflict.txt, feature and clean the first line of merged.txt, and other.txt is the user's.
 */
@DisabledOnOs(OS.WINDOWS)
class MergingWithAutostashTest {
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
        write("conflict.txt" to "base\n", "other.txt" to "o1\n", "merged.txt" to "m1\nm2\nm3\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "switch", "-c", "clean")
        write("merged.txt" to "M1\nm2\nm3\n")
        git.run(repository, "commit", "-am", "Clean")
        git.run(repository, "switch", "-c", "feature", "main")
        write("conflict.txt" to "feature\n", "merged.txt" to "M1\nm2\nm3\n")
        git.run(repository, "commit", "-am", "Feature")
        git.run(repository, "switch", "main")
        write("conflict.txt" to "main\n")
        git.run(repository, "commit", "-am", "Main")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `merge sets aside the changes in the files it merges, and puts them back, where git used to refuse`(): Unit =
        runBlocking {
            autoStash()
            write("merged.txt" to "m1\nm2\nlocal\n")
            open()

            val task = merge("clean")

            assertEquals(CompletedTask.Success(task.date, TaskType.MergeBranch, stoppedAtConflicts = false), task)
            assertEquals("Merge branch 'clean'", subject())
            assertEquals("M1\nm2\nlocal\n", read("merged.txt"))
            assertEquals(listOf(" M merged.txt"), status())
            // Leaf's backup went, and git applied its autostash
            assertEquals(emptyList<String>(), stashes())
            assertEquals(emptyList<AutostashNotice>(), notices())
        }

    @Test
    fun `a merge that stops at conflicts keeps the changes aside, and Leaf's commit puts them back`(): Unit =
        runBlocking {
            autoStash()
            write("other.txt" to "local\n")
            open()

            val task = merge("feature")

            assertEquals(CompletedTask.Success(task.date, TaskType.MergeBranch, stoppedAtConflicts = true), task)
            assertEquals("o1\n", read("other.txt"))
            assertTrue(hasMergeAutostash())

            resolve()
            val commit = tab.doCommit("Merge branch 'feature'", amend = false, author = null).await()

            assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
            assertEquals("local\n", read("other.txt"))
            assertEquals(listOf(" M other.txt"), status())
            assertFalse(hasMergeAutostash())
            // Leaf keeps its backup after conflicts, as before
            assertEquals(listOf(BACKUP_MESSAGE), stashes())
            assertEquals(emptyList<AutostashNotice>(), notices())
        }

    @Test
    fun `Leaf's commit says when the changes don't go back, and the stash list shows them`(): Unit = runBlocking {
        autoStash()
        // The line that feature changes too
        write("merged.txt" to "local\nm2\nm3\n")
        open()
        merge("feature")
        resolve()

        val commit = tab.doCommit("Merge branch 'feature'", amend = false, author = null).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals(listOf("UU merged.txt"), status())
        assertEquals(listOf("autostash", BACKUP_MESSAGE), stashes())
        assertEquals(
            listOf(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.COMMIT, listOf("merged.txt"), 0)),
            notices(),
        )
        // As the side panel names it
        tab.awaitLoaded(tab.repositoryData.stashes) { list -> list.any { it.shortMessage == "On main: autostash" } }
    }

    @Test
    fun `Leaf's abort puts them back, as git merge --abort does`(): Unit = runBlocking {
        autoStash()
        write("other.txt" to "local\n")
        open()
        merge("feature")

        val task = awaitTask { tab.resetRepositoryState() }

        assertEquals(CompletedTask.Success(task.date, TaskType.ResetRepoState), task)
        assertEquals("local\n", read("other.txt"))
        assertEquals(listOf(" M other.txt"), status())
        assertFalse(File(repository, ".git/MERGE_HEAD").exists())
        assertFalse(hasMergeAutostash())
    }

    @Test
    fun `a merge whose changes don't go back says so, and they stay in the stash list`(): Unit = runBlocking {
        autoStash()
        // The line that clean changes
        write("merged.txt" to "local\nm2\nm3\n")
        open()

        val task = merge("clean")

        // git exits with 0: the merge is made
        assertEquals(CompletedTask.Success(task.date, TaskType.MergeBranch, stoppedAtConflicts = false), task)
        assertEquals("Merge branch 'clean'", subject())
        assertEquals(listOf("UU merged.txt"), status())
        assertEquals(listOf("autostash"), stashes())
        assertEquals(
            listOf(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.OPERATION, listOf("merged.txt"), 0)),
            notices(),
        )
    }

    @Test
    fun `Leaf's backup goes when git refuses and changes nothing`(): Unit = runBlocking {
        write("merged.txt" to "local\nm2\nm3\n")
        open()
        val head = head()

        val task = merge("clean")

        assertInstanceOf(CompletedTask.Failure::class.java, task)
        assertEquals(head, head())
        assertEquals("local\nm2\nm3\n", read("merged.txt"))
        assertEquals(emptyList<String>(), stashes())
    }

    @Test
    fun `Leaf's backup stays when a refusing hook leaves the merge in progress`(): Unit = runBlocking {
        write("other.txt" to "local\n")
        File(repository, ".git/hooks/pre-merge-commit").writeExecutable("#!/bin/sh\nexit 1\n")
        open()

        val task = merge("clean")

        assertInstanceOf(CompletedTask.Failure::class.java, task)
        assertTrue(File(repository, ".git/MERGE_HEAD").exists())
        assertEquals(listOf(BACKUP_MESSAGE), stashes())
    }

    @Test
    fun `aborting a squash merge puts the changes back, where git reset --merge would stash them`(): Unit =
        runBlocking {
            autoStash()
            write("other.txt" to "local\n")
            open()
            git.runFailing(repository, "merge", "--squash", "feature")
            assertEquals("o1\n", read("other.txt"))

            val task = awaitTask { tab.abortSquashMerge() }

            assertEquals(CompletedTask.Success(task.date, TaskType.AbortSquashMerge), task)
            assertEquals("local\n", read("other.txt"))
            assertEquals(listOf(" M other.txt"), status())
            assertEquals(emptyList<String>(), stashes())
            assertFalse(hasMergeAutostash())
        }

    @Test
    fun `when git refuses to abort a squash merge, the changes stay aside for later`(): Unit = runBlocking {
        autoStash()
        write("other.txt" to "local\n")
        open()
        git.runFailing(repository, "merge", "--squash", "feature")
        val autostash = git.run(repository, "rev-parse", "MERGE_AUTOSTASH").trim()
        // git refuses while a file that the squash merge changed has unstaged changes
        write("merged.txt" to "edited\n")

        val task = awaitTask { tab.abortSquashMerge() }

        assertInstanceOf(CompletedTask.Failure::class.java, task)
        assertEquals(autostash, git.run(repository, "rev-parse", "MERGE_AUTOSTASH").trim())
        assertEquals("o1\n", read("other.txt"))
        assertEquals(emptyList<String>(), stashes())
    }

    @Test
    fun `Leaf's commit of a squash merge puts the changes back`(): Unit = runBlocking {
        autoStash()
        write("other.txt" to "local\n")
        open()
        // git keeps them aside until the commit, even when the squash merge is clean
        git.run(repository, "merge", "--squash", "clean")
        assertTrue(hasMergeAutostash())

        val commit = tab.doCommit("Squashed clean", amend = false, author = null).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals("local\n", read("other.txt"))
        assertEquals(listOf(" M other.txt"), status())
        assertFalse(hasMergeAutostash())
    }

    private fun autoStash() {
        git.run(repository, "config", "merge.autoStash", "true")
    }

    private suspend fun open() {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)
    }

    /** Merges [branch] as the branch menu does, with Leaf's backup, and returns the task. */
    private suspend fun merge(branch: String): CompletedTask {
        val hash = git.run(repository, "rev-parse", branch).trim()

        return awaitTask { tab.mergeBranch(Branch(hash, "refs/heads/$branch", isLocal = true), BACKUP_MESSAGE) }
    }

    private suspend fun awaitTask(launch: () -> Unit): CompletedTask {
        val before = tab.repositoryState.completedTasks.value.size

        launch()

        return withTimeout(10.seconds) { tab.repositoryState.completedTasks.first { it.size > before } }.last()
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

    private fun read(file: String) = File(repository, file).readText()

    private fun head() = git.run(repository, "rev-parse", "HEAD").trim()

    private fun subject() = git.run(repository, "log", "-1", "--format=%s").trim()

    private fun hasMergeAutostash() =
        runCatching { git.run(repository, "rev-parse", "-q", "--verify", "MERGE_AUTOSTASH") }.isSuccess

    private fun status() = git.run(repository, "status", "--porcelain").lines().filter { it.isNotBlank() }

    private fun stashes() = git.run(repository, "stash", "list", "--format=%gs").lines().filter { it.isNotBlank() }

    private fun notices() = tab.repositoryState.autostashNotices.value.map { it.copy(date = 0) }
}
