// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.errors.DiscardRecordError
import dev.app.leaf.domain.errors.Either
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab that discards and undoes it (fork-only, upstream issue 39): each discard sets the tab's notice, Undo is a task
 * that refreshes the status and marks the notice, and a file that changed since fails it with what changed.
 */
@DisabledOnOs(OS.WINDOWS)
class UndoingDiscardsTest {
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
        File(repository, "a.txt").writeText("a\n")
        File(repository, "b.txt").writeText("b\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Files")

        File(repository, "a.txt").writeText("a, mine\n")
        File(repository, "b.txt").writeText("b, mine\n")
        File(repository, "new.txt").writeText("new\n")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `Discard all sets the notice, and Undo brings everything back and marks it`(): Unit = runBlocking {
        val plan = open().allDiscard()

        tab.discardAll(plan, deleteNewFiles = true).join()
        tab.awaitLoaded(tab.repositoryData.status) { it.unstaged.isEmpty() }
        val record = notice()

        assertEquals(DiscardKind.ALL, record.kind)
        assertEquals(setOf("a.txt", "b.txt", "new.txt"), record.paths.toSet())
        assertEquals(listOf("new.txt"), record.deletedPaths)
        assertEquals(listOf(record), records())

        val restore = (tab.restoreDiscard(record).await() as Either.Ok).value
        val after = tab.awaitLoaded(tab.repositoryData.status) { it.unstaged.size == 3 }

        assertEquals(setOf("a.txt", "b.txt", "new.txt"), after.unstaged.map { it.filePath }.toSet())
        assertEquals("a, mine\n", File(repository, "a.txt").readText())
        assertEquals("new\n", File(repository, "new.txt").readText())
        assertEquals(restore, tab.discardNotices.latest.value?.restore, "The notice says what came back")
        assertEquals(emptyList<DiscardRecord>(), records())
        assertTask(TaskType.RestoreDiscard, CompletedTask.Success::class.java)
    }

    @Test
    fun `Undo of files that all changed since fails with them, and keeps the record`(): Unit = runBlocking {
        val entries = open()

        tab.discardEntries(entries.filter { it.filePath == "a.txt" }, isStaged = false)
        tab.awaitLoaded(tab.repositoryData.status) { status -> status.unstaged.none { it.filePath == "a.txt" } }
        val record = notice()
        File(repository, "a.txt").writeText("a, the agent's\n")

        val error = (tab.restoreDiscard(record).await() as Either.Err).error

        assertEquals(DiscardRecordError.ChangedSince(listOf("a.txt")), error)
        assertEquals("a, the agent's\n", File(repository, "a.txt").readText())
        assertEquals(listOf(record.id), records().map { it.id })
        assertNull(tab.discardNotices.latest.value?.restore)
        assertTask(TaskType.RestoreDiscard, CompletedTask.Failure::class.java)
    }

    @Test
    fun `Delete file, then Undo from the list of recent discards`(): Unit = runBlocking {
        open()

        tab.deleteFile("new.txt")
        tab.awaitLoaded(tab.repositoryData.status) { status -> status.unstaged.none { it.filePath == "new.txt" } }
        val record = notice()

        assertEquals(DiscardKind.DELETE, record.kind)
        assertFalse(File(repository, "new.txt").exists())

        val plan = (tab.planDiscardRestore(record) as Either.Ok).value
        assertEquals(listOf("new.txt"), plan.restorable)

        tab.restoreDiscard(records().single()).await()
        tab.awaitLoaded(tab.repositoryData.status) { status -> status.unstaged.any { it.filePath == "new.txt" } }

        assertEquals("new\n", File(repository, "new.txt").readText())
    }

    private suspend fun open() = run {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status).unstaged
    }

    private suspend fun notice(): DiscardRecord = tab.discardNotices.latest.first { it != null }!!.record

    private suspend fun records() = (tab.getDiscardRecords() as Either.Ok).value

    private suspend fun assertTask(type: TaskType, outcome: Class<out CompletedTask>) {
        val task = tab.repositoryState.completedTasks.first { tasks -> tasks.any { it.taskType == type } }
            .last { it.taskType == type }

        assertInstanceOf(outcome, task, task.toString())
    }
}
