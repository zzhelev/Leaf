// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.sorting.allDiscard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/**
 * A tab that discards all the unstaged changes (fork-only): the task records its outcome, and the status refreshes on
 * its own, so Unstaged shows what is left right after, without waiting for the file watcher.
 */
@DisabledOnOs(OS.WINDOWS)
class DiscardingAllTest {
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

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config").apply { mkdirs() }, originalReader))

        git.initRepository(repository)
        File(repository, "a.txt").writeText("a")
        File(repository, "src").mkdirs()
        File(repository, "src/gone.txt").writeText("gone")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Files")

        File(repository, "a.txt").writeText("a changed")
        File(repository, "src/gone.txt").delete()
        File(repository, "new.txt").writeText("new")
        File(repository, "agent/out").mkdirs()
        File(repository, "agent/out/log.txt").writeText("log")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `restores the tracked files and leaves the new ones, and Unstaged shows what is left`(): Unit = runBlocking {
        val plan = open().allDiscard()

        tab.discardAll(plan, deleteNewFiles = false).join()

        val after = tab.awaitLoaded(tab.repositoryData.status) { status ->
            status.unstaged.none { it.filePath == "a.txt" }
        }

        assertEquals(setOf("new.txt", "agent/out/log.txt"), after.unstaged.map { it.filePath }.toSet())
        assertEquals("a", File(repository, "a.txt").readText())
        assertEquals("gone", File(repository, "src/gone.txt").readText())
        assertSuccess()
    }

    @Test
    fun `with the new files too, Unstaged is empty and the folders they filled are gone`(): Unit = runBlocking {
        val plan = open().allDiscard()

        tab.discardAll(plan, deleteNewFiles = true).join()

        val after = tab.awaitLoaded(tab.repositoryData.status) { status -> status.unstaged.isEmpty() }

        assertEquals(emptyList<String>(), after.unstaged.map { it.filePath })
        assertFalse(File(repository, "agent").exists())
        assertFalse(File(repository, "new.txt").exists())
        assertSuccess()
    }

    @Test
    fun `a file that can't be deleted fails the task, after the changes were restored`(): Unit = runBlocking {
        val plan = open().allDiscard()
        val locked = File(repository, "agent/out")

        locked.setWritable(false)

        try {
            tab.discardAll(plan, deleteNewFiles = true).join()

            val task = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()
            val failure = assertInstanceOf(CompletedTask.Failure::class.java, task, task.toString())

            assertEquals(TaskType.DiscardAllFiles, failure.taskType)
            assertEquals("a", File(repository, "a.txt").readText(), "The restore came first")
            assertEquals("log", File(locked, "log.txt").readText())

            // The status refreshes after a failure too, so the pane shows what is still there
            val after = tab.awaitLoaded(tab.repositoryData.status) { status ->
                status.unstaged.none { it.filePath == "a.txt" }
            }

            assertEquals(true, "agent/out/log.txt" in after.unstaged.map { it.filePath })
        } finally {
            locked.setWritable(true)
        }
    }

    private suspend fun open() = run {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status).unstaged
    }

    private suspend fun assertSuccess() {
        val task = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()

        assertEquals(TaskType.DiscardAllFiles, task.taskType, task.toString())
        assertInstanceOf(CompletedTask.Success::class.java, task, task.toString())
    }
}
