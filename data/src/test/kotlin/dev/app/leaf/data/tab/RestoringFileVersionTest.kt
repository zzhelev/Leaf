// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RestoreFileError
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.FailureSeverity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that restores a file's version from file history (fork-only): the task says how it ended, the result goes back
 * to file history, which tells the user, and the status refreshes on its own, so the file shows in Unstaged right after.
 */
@DisabledOnOs(OS.WINDOWS)
class RestoringFileVersionTest {
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

    private val repository by lazy { File(tempDir, "repo") }
    private lateinit var first: String
    private lateinit var second: String

    @BeforeEach
    fun createRepository() {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config").apply { mkdirs() }, originalReader))

        git.initRepository(repository)
        File(repository, "a.txt").writeText("version 1\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "One")
        first = git.run(repository, "rev-parse", "HEAD").trim()
        File(repository, "a.txt").writeText("version 2\n")
        git.run(repository, "commit", "-am", "Two")
        second = git.run(repository, "rev-parse", "HEAD").trim()
    }

    @AfterEach
    fun tearDown() {
        if (builtAskpassHelper() != null) tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `restores the version, the task says so, and Unstaged shows the file right after`(): Unit = runBlocking {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status) { it.unstaged.isEmpty() }

        val result = tab.restoreFileVersion(commit(first), "a.txt").await()

        assertEquals(Either.Ok(Unit), result)
        assertEquals("version 1\n", File(repository, "a.txt").readText())

        val after = tab.awaitLoaded(tab.repositoryData.status) { status -> status.unstaged.isNotEmpty() }

        assertEquals(listOf("a.txt" to StatusType.MODIFIED), after.unstaged.map { it.filePath to it.statusType })
        assertEquals(emptyList<String>(), after.staged.map { it.filePath }, "nothing is staged")

        val task = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()
        assertEquals(TaskType.RestoreFileVersion, task.taskType)
        assertInstanceOf(CompletedTask.Success::class.java, task, task.toString())
    }

    @Test
    fun `a refusal is the task's failure, in the error dialog, and the status refreshes anyway`(): Unit = runBlocking {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status) { it.unstaged.isEmpty() }
        File(repository, "a.txt").delete()
        File(repository, "a.txt").mkdirs()
        File(repository, "a.txt/mine.txt").writeText("untracked work\n")

        val result = tab.restoreFileVersion(commit(first), "a.txt").await()

        assertEquals(Either.Err(RestoreFileError.FolderInPlace("a.txt")), result)

        val task = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()
        val failure = assertInstanceOf(CompletedTask.Failure::class.java, task, task.toString())
        assertEquals(TaskType.RestoreFileVersion, failure.taskType)
        assertEquals(FailureSeverity.HIGH, failure.severity)
        assertEquals("untracked work\n", File(repository, "a.txt/mine.txt").readText())

        // The refresh after a failure is what shows the file as it is
        val after = tab.awaitLoaded(tab.repositoryData.status) { status ->
            status.unstaged.any { it.filePath == "a.txt" || it.filePath == "a.txt/mine.txt" }
        }
        assertEquals(true, after.unstaged.isNotEmpty())
    }

    private fun commit(hash: String) = dev.app.leaf.domain.models.Commit(
        hash = hash,
        message = "Commit",
        committer = dev.app.leaf.domain.models.Identity("Leaf Test", "test@example.invalid"),
        author = dev.app.leaf.domain.models.Identity("Leaf Test", "test@example.invalid"),
        date = 1_760_000_000,
        parentsHashes = emptyList(),
    )
}
