// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.models.CleanupCandidate
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.WorktreeCleanup
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.candidates
import dev.app.leaf.domain.worktrees.cleanupSteps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

private const val HOUR = 60 * 60 * 1000L

/**
 * "Clean up merged worktrees" (Phase 4.3b) in a tab, on a repository made with the git CLI: main, and next to it a
 * squash-merged worktree, a merged one with an untracked file, a fresh one, and a missing one whose branch has a commit
 * that main doesn't. The tab reads what the dialog shows and runs the steps that it would run.
 */
@DisabledOnOs(OS.WINDOWS)
class CleaningUpWorktreesTest {
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
    private val squashed by lazy { File(tempDir, "squashed") }
    private val merged by lazy { File(tempDir, "merged") }
    private val fresh by lazy { File(tempDir, "fresh") }
    private val missing by lazy { File(tempDir, "missing") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init", "-b", "main")
        commit(main, "README.md", "Initial commit")

        for (worktree in listOf(squashed, merged, fresh, missing)) {
            git.run(main, "worktree", "add", "-b", worktree.name, worktree.path)
        }

        commit(squashed, "squashed.txt", "Squashed work")
        git.run(main, "merge", "--squash", "squashed")
        git.run(main, "commit", "-m", "Squash squashed")

        commit(merged, "merged.txt", "Merged work")
        git.run(main, "merge", "--no-ff", "-m", "Merge merged", "merged")
        File(merged, "draft.txt").writeText("draft\n")

        commit(missing, "missing.txt", "Unmerged work")
        missing.deleteRecursively()

        // Nothing changed in the last few minutes, where an agent may still be working
        setTimesBack(squashed, merged, fresh)
        File(merged, "draft.txt").setLastModified(System.currentTimeMillis() - HOUR)
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `offers merged and missing worktrees, and checks the ones that lose nothing`(): Unit = runBlocking {
        open(main)

        val candidates = cleanup().candidates

        // git lists linked worktrees by name; the checked ones come first
        assertEquals(listOf(missing, squashed, merged).map { it.canonicalPath }, candidates.map { it.worktree.path })
        assertEquals(listOf(true, true, false), candidates.map { it.isCheckedAtFirst })

        val (missingCandidate, squashedCandidate, mergedCandidate) = candidates
        assertEquals(MergedIntoBase.CHANGES, squashedCandidate.info.merged)
        assertEquals("squashed", squashedCandidate.branch)
        assertNull(missingCandidate.info.merged)
        assertNull(missingCandidate.branch, "its commit isn't in main")
        assertEquals(MergedIntoBase.COMMITS, mergedCandidate.info.merged)
        assertEquals(1, mergedCandidate.changes)
        assertNull(mergedCandidate.recentChange)
    }

    @Test
    fun `removes the checked worktrees and their branches, and keeps the rest`(): Unit = runBlocking {
        open(squashed)
        val cleanup = cleanup()
        val steps = steps(cleanup, cleanup.candidates.filter { it.isCheckedAtFirst })

        assertEquals(WorktreeStep.SwitchTab(cleanup.list.mainWorktree!!.path), steps.first())

        tab.runWorktreeSteps(TaskType.CleanUpWorktrees, steps).join()

        assertSuccess()
        assertShows(main)
        assertFalse(squashed.exists())
        assertEquals(listOf(main, fresh, merged).map { it.canonicalPath }, gitWorktreeList())
        assertEquals(listOf("fresh", "main", "merged", "missing"), branches(), "the missing worktree's branch stays")
        tab.awaitLoaded(tab.repositoryData.worktrees) { it.worktrees.size == 3 }
    }

    @Test
    fun `a merged worktree with changes goes once checked, with its changes`(): Unit = runBlocking {
        open(main)
        val cleanup = cleanup()

        tab.runWorktreeSteps(TaskType.CleanUpWorktrees, steps(cleanup, cleanup.candidates)).join()

        assertSuccess()
        assertFalse(merged.exists())
        assertEquals(listOf(main, fresh).map { it.canonicalPath }, gitWorktreeList())
        assertEquals(listOf("fresh", "main", "missing"), branches())
    }

    @Test
    fun `a change made after the dialog read the worktrees stops the cleanup, and loses nothing`(): Unit =
        runBlocking {
            open(main)
            val cleanup = cleanup()
            val squashedCandidate = cleanup.candidates.single { it.worktree.path == squashed.canonicalPath }
            File(squashed, "late.txt").writeText("an agent's late change\n")

            tab.runWorktreeSteps(TaskType.CleanUpWorktrees, steps(cleanup, listOf(squashedCandidate))).join()

            val failure = tab.repositoryState.completedTasks.first().last() as CompletedTask.Failure
            val error = assertInstanceOf(GitCliError.CommandFailed::class.java, failure.reason)
            assertTrue("contains modified or untracked files" in error.stderr, error.stderr)
            assertTrue(File(squashed, "late.txt").exists())
            assertTrue("squashed" in branches())
        }

    @Test
    fun `a merged worktree that changed in the last few minutes is left unchecked`(): Unit = runBlocking {
        File(squashed, "squashed.txt").appendText("Still working\n")
        open(main)

        val candidate = cleanup().candidates.single { it.worktree.path == squashed.canonicalPath }

        assertNotNull(candidate.recentChange)
        assertFalse(candidate.isCheckedAtFirst)
    }

    private suspend fun open(worktree: File) {
        tab.openRepository(worktree.path)
        tab.awaitLoaded(tab.repositoryData.worktrees) { it.worktrees.size == 5 }
        tab.awaitLoaded(tab.repositoryData.status)
    }

    private suspend fun cleanup(): WorktreeCleanup = (tab.getWorktreeCleanup() as Either.Ok).value

    private fun steps(cleanup: WorktreeCleanup, candidates: List<CleanupCandidate>) =
        cleanupSteps(cleanup.list.mainWorktree!!, candidates, deleteBranches = true)

    private suspend fun assertSuccess() {
        val task = tab.repositoryState.completedTasks.first().last()

        assertEquals(TaskType.CleanUpWorktrees, task.taskType, task.toString())
        assertInstanceOf(CompletedTask.Success::class.java, task, task.toString())
    }

    private fun assertShows(worktree: File) {
        val state = tab.repositoryData.repositorySelectionState.value
        val openPath = assertInstanceOf(RepositorySelectionState.Open::class.java, state).path
        val gitDir = File(git.run(worktree, "rev-parse", "--absolute-git-dir").trim()).canonicalPath

        assertEquals(gitDir, File(openPath).canonicalPath)
    }

    private fun gitWorktreeList(): List<String> = git.run(main, "worktree", "list", "--porcelain")
        .lines()
        .filter { it.startsWith("worktree ") }
        .map { File(it.removePrefix("worktree ")).canonicalPath }

    private fun branches(): List<String> =
        git.run(main, "branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

    /** Sets back the files that git writes in each worktree's git dir as it works. */
    private fun setTimesBack(vararg worktrees: File) {
        val time = System.currentTimeMillis() - HOUR

        for (worktree in worktrees) {
            val gitDir = File(main, ".git/worktrees/${worktree.name}")

            for (file in listOf("index", "HEAD", "logs/HEAD")) {
                File(gitDir, file).takeIf { it.exists() }?.setLastModified(time)
            }
        }
    }

    private fun commit(directory: File, fileName: String, message: String) {
        File(directory, fileName).appendText("$message\n")
        git.run(directory, "add", fileName)
        git.run(directory, "commit", "-m", message)
    }
}
