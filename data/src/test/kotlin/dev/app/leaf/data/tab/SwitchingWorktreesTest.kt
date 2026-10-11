// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.OpenRepoError
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.FailureSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A tab that switches between the worktrees of a repository made with the git CLI: main, and feature next to it. Each
 * has a file of its own that isn't committed, and feature has a commit message being written.
 */
@DisabledOnOs(OS.WINDOWS)
class SwitchingWorktreesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tab by lazy {
        TestTab(
            testJGit(),
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
            // Slower than a test's own steps, so that one sees whether a switch waits for the status
            statusDelay = 300.milliseconds,
        )
    }

    private val main by lazy { File(tempDir, "main") }
    private val feature by lazy { File(tempDir, "feature") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init", "-b", "main")
        File(main, "README.md").writeText("readme\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")
        git.run(main, "worktree", "add", feature.path, "-b", "feature")

        File(main, "main-change.txt").writeText("main\n")
        File(feature, "feature-change.txt").writeText("feature\n")
        File(gitDir(feature), "COMMIT_EDITMSG").writeText("Feature draft")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `shows the other worktree's changes, branch and message, and watches it`(): Unit = runBlocking {
        openMain()
        // Every list of worktrees the tab shows, by the one it marks as the tab's. Unconfined, it misses none.
        val currentWorktrees = CopyOnWriteArrayList<String>()
        val recording = launch(Dispatchers.Unconfined) {
            tab.repositoryData.worktrees.collect { state ->
                if (state is DataState.Loaded) {
                    currentWorktrees += state.data.worktrees.single { it.worktree.isCurrent }.worktree.path
                }
            }
        }

        tab.switchWorktree(feature.path).join()

        assertShows(feature)
        // Input is blocked until the status shows the worktree's own files
        val status = tab.repositoryData.status.first()
        assertEquals(listOf("feature-change.txt"), (status as DataState.Loaded).data.unstaged.map { it.filePath })
        // and the worktree list marked the worktree as the tab's before git listed them again, which comes last
        assertTrue(feature.canonicalPath in currentWorktrees, currentWorktrees.toString())
        recording.cancel()
        tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.name == "refs/heads/feature" }
        tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.commitMessage == "Feature draft" }
        tab.awaitLoaded(tab.repositoryData.worktrees) { list ->
            list.worktrees.single { it.worktree.isCurrent }.worktree.path == feature.canonicalPath
        }

        val featureWatcher = tab.awaitWatch(2)
        withTimeout(5.seconds) {
            while (!tab.watchers.first().isClosed) delay(10)
        }
        assertTrue(featureWatcher.watchedPaths.any { File(it).canonicalPath == feature.canonicalPath })
    }

    @Test
    fun `switches back to the main worktree`(): Unit = runBlocking {
        openMain()
        tab.switchWorktree(feature.path).join()
        tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.name == "refs/heads/feature" }

        tab.switchWorktree(main.path).join()

        assertShows(main)
        awaitChanges("main-change.txt")
        tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.name == "refs/heads/main" }
        tab.awaitWatch(3)
    }

    @Test
    fun `stays on its worktree when the other one can't be opened`(): Unit = runBlocking {
        openMain()
        val missing = File(tempDir, "missing")

        tab.switchWorktree(missing.path).join()

        assertShows(main)
        val failure = tab.repositoryState.completedTasks.first().last() as CompletedTask.Failure
        assertEquals(TaskType.SwitchWorktree, failure.taskType)
        assertEquals(FailureSeverity.HIGH, failure.severity)
        assertInstanceOf(OpenRepoError.DirectoryNotFoundError::class.java, failure.reason)
        assertEquals(1, tab.watchers.size, "the main worktree's watcher is still the only one")
    }

    @Test
    fun `does nothing when switching to the worktree that the tab shows`(): Unit = runBlocking {
        openMain()

        tab.switchWorktree(main.path).join()

        assertShows(main)
        assertEquals(1, tab.watchers.size)
        assertEquals(false, tab.watchers.single().isClosed)
    }

    @Test
    fun `a tab restored on a removed worktree opens its repository`(): Unit = runBlocking {
        val featureGitDir = gitDir(feature)
        git.run(main, "worktree", "remove", "--force", feature.path)

        tab.openRepository(featureGitDir)

        assertShows(main)
        tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.name == "refs/heads/main" }
    }

    @Test
    fun `a tab restored on a worktree whose folder was deleted opens its repository`(): Unit = runBlocking {
        val featureGitDir = gitDir(feature)
        feature.deleteRecursively()

        tab.openRepository(featureGitDir)

        assertShows(main)
    }

    @Test
    fun `a tab restored on a worktree that's still there opens it`(): Unit = runBlocking {
        tab.openRepository(gitDir(feature))

        assertShows(feature)
    }

    /** Waits until the status shows [file] as the only change, which only one worktree has. */
    private suspend fun awaitChanges(file: String) {
        tab.awaitLoaded(tab.repositoryData.status) { status -> status.unstaged.map { it.filePath } == listOf(file) }
    }

    /** Checks that the tab shows [worktree]: it has its git dir open, whichever path git or the tab gave it. */
    private fun assertShows(worktree: File) {
        val state = tab.repositoryData.repositorySelectionState.value

        val openPath = assertInstanceOf(RepositorySelectionState.Open::class.java, state).path
        assertEquals(File(gitDir(worktree)).canonicalPath, File(openPath).canonicalPath)
    }

    private suspend fun openMain() {
        tab.openRepository(main.path)
        awaitChanges("main-change.txt")
        tab.awaitLoaded(tab.repositoryData.worktrees)
        tab.awaitWatch(1)
    }

    private fun gitDir(worktree: File): String = git.run(worktree, "rev-parse", "--absolute-git-dir").trim()
}
