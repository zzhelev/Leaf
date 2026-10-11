// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.models.RepositorySelectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The file watcher of a tab, which watches each worktree that the tab shows with a watcher of its own: a closed
 * watcher can't watch again.
 */
@DisabledOnOs(OS.WINDOWS)
class WatchingWorktreesTest {
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
    private val feature by lazy { File(tempDir, "feature") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        File(main, "src").mkdirs()
        git.run(main, "init", "-b", "main")
        File(main, "src/main.txt").writeText("main\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")
        git.run(main, "worktree", "add", feature.path, "-b", "feature")
        File(feature, "feature-only").mkdirs()
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `watches the worktree that the tab opened`(): Unit = runBlocking {
        tab.openRepository(main.path)

        val watcher = tab.awaitWatch(1)
        awaitWatching(watcher, File(main, "src"))

        assertTrue(watcher.watches(main))
        assertFalse(watcher.isClosed)
    }

    @Test
    fun `watches another worktree with a new watcher, and closes the old one`(): Unit = runBlocking {
        tab.openRepository(main.path)
        val mainWatcher = tab.awaitWatch(1)
        awaitWatching(mainWatcher, File(main, "src"))

        tab.repositoryData.setRepositorySelectionState(RepositorySelectionState.Open(gitDir(feature)))
        tab.observeRepositoryToRefresh()

        val featureWatcher = tab.awaitWatch(2)
        awaitWatching(featureWatcher, File(feature, "feature-only"))
        withTimeout(5.seconds) {
            while (!mainWatcher.isClosed) delay(10)
        }

        assertTrue(featureWatcher.watches(feature))
        assertFalse(featureWatcher.watches(main), "the main worktree's folders: ${featureWatcher.watchedPaths}")
        assertFalse(featureWatcher.isClosed)
        assertEquals(2, tab.watchers.size)
    }

    @Test
    fun `stops watching, and closes the watcher`(): Unit = runBlocking {
        tab.openRepository(main.path)
        val watcher = tab.awaitWatch(1)
        awaitWatching(watcher, File(main, "src"))

        tab.observeRepositoryToRefresh.stop()

        withTimeout(5.seconds) {
            while (!watcher.isClosed) delay(10)
        }
    }

    private suspend fun awaitWatching(watcher: RecordingFileChangesWatcher, folder: File) = withTimeout(10.seconds) {
        while (!watcher.watchedPaths.any { File(it).canonicalPath == folder.canonicalPath }) {
            delay(10)
        }
    }

    /** Whether the watcher watches [worktree]'s folder itself, which is watched first. */
    private fun RecordingFileChangesWatcher.watches(worktree: File) =
        watchedPaths.any { File(it).canonicalPath == worktree.canonicalPath }

    private fun gitDir(worktree: File): String {
        val gitDir = git.run(worktree, "rev-parse", "--git-dir").trim()

        return if (File(gitDir).isAbsolute) gitDir else File(worktree, gitDir).path
    }
}
