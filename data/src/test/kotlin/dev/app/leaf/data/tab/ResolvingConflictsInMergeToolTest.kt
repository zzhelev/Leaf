// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.conflicts.OpenMergeToolGitAction
import dev.app.leaf.data.git.conflicts.mergeWithConflicts
import dev.app.leaf.data.git.conflicts.mergeWithConflictsIn
import dev.app.leaf.data.git.conflicts.stages
import dev.app.leaf.data.git.conflicts.staged
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.conflicts.ConflictsToOpen
import dev.app.leaf.domain.conflicts.conflictsToOpen
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.usecases.OpenMergeToolUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.time.Duration.Companion.seconds

private const val RESOLVED = "resolved line\n"

/**
 * The banner's Resolve conflicts with the external merge tool picked, through a tab's use cases: the files that
 * `conflictsToOpen` picks from the tab's status, one after another, with a fake tool set up as `mergetool.fake.cmd`.
 * Its exit code is trusted, unless a test says otherwise.
 */
@DisabledOnOs(OS.WINDOWS)
class ResolvingConflictsInMergeToolTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit = testJGit()
    private val gitCli by lazy {
        testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
    }
    private val tab by lazy { TestTab(jgit, gitCli) }
    private val openMergeTool by lazy {
        OpenMergeToolUseCase(OpenMergeToolGitAction(jgit, gitCli, tab.repositoryState), tab.useCaseExecutor)
    }

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val tools by lazy { File(tempDir, "tools").apply { mkdirs() } }

    /** The files that the tool was given, in order. */
    private val opened by lazy { File(tools, "opened") }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun closeTab() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `every file that the tool can take is resolved in turn, and the others are left for the resolver`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            setTool()

            assertEquals(ConflictsToOpen.InMergeTool(listOf("added.txt", "notes.txt")), resolveConflicts())

            assertEquals(listOf("added.txt", "notes.txt"), opened.readLines())
            assertEquals(RESOLVED, git.staged(repository, "added.txt"))
            assertEquals(RESOLVED, git.staged(repository, "notes.txt"))
            // One side deleted them: git would ask which to keep
            assertEquals(listOf(1, 3), git.stages(repository, "dropped.txt"))
            assertEquals(listOf(1, 2), git.stages(repository, "kept.txt"))
            assertSucceeded()

            // The status refreshed: a second click opens what's left in the built-in resolver
            awaitConflicts(2)
            assertEquals(ConflictsToOpen.InResolver("dropped.txt"), toOpen())
        }

    @Test
    fun `a file closed without saving stops it there, saying how many it resolved before`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "a.txt", "b.txt", "c.txt")
        // git doesn't trust its exit code, so it asks "Was the merge successful?" about b.txt, which isn't saved
        setTool(unless = "b.txt", trustExitCode = false)

        resolveConflicts()

        assertEquals(listOf("a.txt", "b.txt"), opened.readLines())
        assertEquals(RESOLVED, git.staged(repository, "a.txt"))
        assertEquals(listOf(1, 2, 3), git.stages(repository, "b.txt"))
        assertEquals(listOf(1, 2, 3), git.stages(repository, "c.txt"))

        val failure = assertInstanceOf(CompletedTask.Failure::class.java, tab.repositoryState.completedTasks.value.last())
        assertEquals(TaskType.ResolveConflictsInMergeTool, failure.taskType)
        val error = assertInstanceOf(ConflictError.NotResolved::class.java, failure.reason)
        assertEquals("b.txt", error.path)
        assertEquals(1, error.resolvedBefore)
        assertTrue("merge of b.txt failed" in error.output, error.output)

        // Refreshed even so: the next click starts from b.txt
        awaitConflicts(2)
        assertEquals(ConflictsToOpen.InMergeTool(listOf("b.txt", "c.txt")), toOpen())
    }

    @Test
    fun `a trusted tool that leaves markers stops it too, with the conflict back`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "a.txt", "b.txt", "c.txt")
        setTool(unless = "b.txt")

        resolveConflicts()

        assertEquals(listOf("a.txt", "b.txt"), opened.readLines())
        val failure = assertInstanceOf(CompletedTask.Failure::class.java, tab.repositoryState.completedTasks.value.last())
        assertEquals(ConflictError.MarkersLeft("fake", "b.txt", resolvedBefore = 1), failure.reason)
        assertEquals(listOf(1, 2, 3), git.stages(repository, "b.txt"))
        assertEquals(listOf(1, 2, 3), git.stages(repository, "c.txt"))
    }

    @Test
    fun `a file that something else resolved meanwhile is skipped`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "a.txt", "b.txt", "c.txt")
        setTool()
        openInTab()
        val toOpen = toOpen() as ConflictsToOpen.InMergeTool

        // An agent, or a terminal, after the status was read
        git.run(repository, "add", "b.txt")
        awaitTask { openMergeTool.inTurn(toOpen.filePaths) }

        assertEquals(listOf("a.txt", "c.txt"), opened.readLines())
        assertEquals(RESOLVED, git.staged(repository, "c.txt"))
        // Staged as the agent left it, which the tool never saw
        assertEquals(listOf(0), git.stages(repository, "b.txt"))
        assertSucceeded()
    }

    @Test
    fun `while the tool is open, the processing screen says which file of how many`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "a.txt", "b.txt", "c.txt")
        val started = File(tools, "started")
        val go = File(tools, "go")
        // Waits on b.txt until the test lets it go
        val waiting = "if [ \"\$1\" = b.txt ]; then touch '${started.path}'; " +
            "while [ ! -f '${go.path}' ]; do sleep 0.05; done; fi"
        setTool(beforeSaving = waiting)
        openInTab()
        val toOpen = toOpen() as ConflictsToOpen.InMergeTool

        openMergeTool.inTurn(toOpen.filePaths)

        withTimeout(30.seconds) {
            while (!started.exists()) {
                delay(50)
            }
        }
        assertEquals(TaskType.ResolveConflictsInMergeTool, tab.repositoryState.currentTask.value)
        assertEquals(TaskProgress(stage = "fake: b.txt (2 of 3)", percent = null), tab.repositoryState.taskProgress.value)

        go.createNewFile()
        withTimeout(30.seconds) { tab.repositoryState.currentTask.first { it == null } }

        assertEquals(listOf("a.txt", "b.txt", "c.txt"), opened.readLines())
        assertSucceeded()
    }

    /** Opens the repository in the tab, and does what the banner's button does, with the merge tool picked. */
    private suspend fun resolveConflicts(): ConflictsToOpen {
        openInTab()
        val toOpen = toOpen()

        awaitTask { openMergeTool.inTurn((toOpen as ConflictsToOpen.InMergeTool).filePaths) }

        return toOpen
    }

    private suspend fun openInTab() {
        tab.openRepository(File(repository, ".git").path)
        awaitConflicts(null)
    }

    /** What the banner's button opens for the tab's status, with the merge tool picked. */
    private suspend fun toOpen(): ConflictsToOpen =
        conflictsToOpen(tab.awaitLoaded(tab.repositoryData.status).unstaged, inMergeTool = true)

    /** Waits until the tab's status has [count] conflicted files, or any when null. */
    private suspend fun awaitConflicts(count: Int?) {
        tab.awaitLoaded(tab.repositoryData.status) { status ->
            val conflicts = status.unstaged.count { it.statusType == StatusType.CONFLICTING }

            if (count == null) conflicts > 0 else conflicts == count
        }
    }

    /** Starts a task that can't be joined, and waits until it ends. */
    private suspend fun awaitTask(start: () -> Unit) {
        val before = tab.repositoryState.completedTasks.value.size

        start()

        withTimeout(30.seconds) { tab.repositoryState.completedTasks.first { it.size > before } }
    }

    private fun assertSucceeded() {
        val task = tab.repositoryState.completedTasks.value.last()

        assertEquals(CompletedTask.Success(task.date, TaskType.ResolveConflictsInMergeTool), task)
    }

    /**
     * Sets up the merge tool `fake`, which notes each file it's given in [opened], runs [beforeSaving], and writes
     * [RESOLVED] to the file, except to [unless], which keeps its markers. git compares the file's time with its
     * backup's, to the second, so a saved file gets a later one.
     */
    private fun setTool(unless: String? = null, trustExitCode: Boolean = true, beforeSaving: String = "") {
        val saving = "printf 'resolved line\\n' > \"\$1\"; touch -t 203001010000 \"\$1\""
        val script = listOf(
            "#!/bin/sh",
            "echo \"\$1\" >> '${opened.path}'",
            beforeSaving,
            "[ \"\$1\" = '${unless.orEmpty()}' ] || { $saving; }",
        ).joinToString("\n", postfix = "\n")
        val tool = File(tools, "fake").writeExecutable(script)

        git.run(repository, "config", "merge.tool", "fake")
        git.run(repository, "config", "mergetool.fake.cmd", "'${tool.path}' \"\$MERGED\"")
        git.run(repository, "config", "mergetool.fake.trustExitCode", trustExitCode.toString())
    }
}
