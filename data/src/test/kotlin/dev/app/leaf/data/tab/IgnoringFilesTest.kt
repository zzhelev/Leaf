// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.IgnoreError
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that adds ignore rules for untracked files (fork-only): the task records its outcome, and the status refreshes
 * on its own, so the files are gone from Unstaged right after, without waiting for the file watcher.
 */
@DisabledOnOs(OS.WINDOWS)
class IgnoringFilesTest {
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
        val config = File(tempDir, "config").apply { mkdirs() }
        val settings = "[core]\n\texcludesFile = ${File(config, "global-ignore").apply { writeText("") }.path}\n"

        File(config, "global.gitconfig").writeText(settings)
        File(config, "user.gitconfig").writeText(settings)
        SystemReader.setInstance(IsolatedSystemReader(config, originalReader))

        git.initRepository(repository)
        File(repository, "build").mkdirs()
        File(repository, "build/out.log").writeText("log")
        File(repository, "build/cache.bin").writeText("bin")
        File(repository, "notes.txt").writeText("notes")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `ignoring a folder takes its files out of Unstaged once the task ends`(): Unit = runBlocking {
        open()

        val before = untracked()

        assertEquals(setOf("build/out.log", "build/cache.bin", "notes.txt"), before)

        tab.addIgnoreRules(IgnorePlace.GITIGNORE, listOf("/build/")).join()

        // The refresh that the task asks for has run by the time the status shows the change
        val after = tab.awaitLoaded(tab.repositoryData.status) { status ->
            status.unstaged.none { it.filePath.startsWith("build/") }
        }

        assertEquals(setOf(".gitignore", "notes.txt"), after.unstaged.map { it.filePath }.toSet())
        assertEquals("/build/\n", File(repository, ".gitignore").readText())
        assertSuccess()
    }

    @Test
    fun `ignoring in the local exclude file changes nothing in the working tree, and the status still refreshes`(): Unit =
        runBlocking {
            open()

            tab.addIgnoreRules(IgnorePlace.EXCLUDE, listOf("*.log")).join()

            val after = tab.awaitLoaded(tab.repositoryData.status) { status ->
                status.unstaged.none { it.filePath.endsWith(".log") }
            }

            assertEquals(setOf("build/cache.bin", "notes.txt"), after.unstaged.map { it.filePath }.toSet())
            assertTrue(!File(repository, ".gitignore").exists())
            assertSuccess()
        }

    @Test
    fun `a symbolic gitignore fails the task and writes nothing`(): Unit = runBlocking {
        val target = File(tempDir, "outside/real.ignore").apply { parentFile.mkdirs(); writeText("keep\n") }
        File(repository, ".gitignore").symlinkTo(target)
        open()

        tab.addIgnoreRules(IgnorePlace.GITIGNORE, listOf("/build/")).join()

        val task = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()
        val failure = assertInstanceOf(CompletedTask.Failure::class.java, task, task.toString())

        assertEquals(TaskType.AddIgnoreRules, failure.taskType)
        assertInstanceOf(IgnoreError.FileNotWritable::class.java, failure.reason)
        assertEquals("keep\n", target.readText())
    }

    private suspend fun open() {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun untracked(): Set<String> =
        tab.awaitLoaded(tab.repositoryData.status).unstaged.map { it.filePath }.toSet()

    private suspend fun assertSuccess() {
        val task = tab.repositoryState.completedTasks.first { it.isNotEmpty() }.last()

        assertEquals(TaskType.AddIgnoreRules, task.taskType, task.toString())
        assertInstanceOf(CompletedTask.Success::class.java, task, task.toString())
    }
}
