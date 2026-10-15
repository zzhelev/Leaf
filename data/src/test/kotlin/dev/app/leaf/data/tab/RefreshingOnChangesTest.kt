// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * What a tab refreshes when its file watcher reports changes. Most tests use the app's watcher: the file system's
 * events, through the watch's batching, to the refresh.
 */
@DisabledOnOs(OS.WINDOWS)
class RefreshingOnChangesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tabs = mutableListOf<TestTab>()

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
    }

    @AfterEach
    fun tearDown() {
        tabs.forEach { it.close() }
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `an edit in a folder of the working tree refreshes the status`(): Unit = runBlocking {
        val tab = openTab(realFileWatcher = true)

        // Edited again until the watch has the folder: it starts watching after the first status
        repeatUntil({ tab.unstagedFiles().contains("src/main.txt") }) {
            File(main, "src/main.txt").writeText("edited ${System.nanoTime()}\n")
        }
    }

    @Test
    fun `a commit made in a terminal refreshes the current branch`(): Unit = runBlocking {
        val tab = openTab(realFileWatcher = true)
        // Lets the watch start, as the commit happens once
        delay(1.seconds)

        File(main, "src/main.txt").writeText("committed\n")
        git.run(main, "commit", "-am", "Second commit")
        val commit = git.run(main, "rev-parse", "HEAD").trim()

        tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.hash == commit }
    }

    @Test
    fun `staging in another worktree refreshes the worktree list`(): Unit = runBlocking {
        val tab = openTab(realFileWatcher = true)
        tab.awaitLoaded(tab.repositoryData.worktrees) { list -> list.worktrees.size == 2 }
        delay(1.seconds)

        File(feature, "src/main.txt").writeText("feature\n")
        // Writes the feature worktree's index, in the main worktree's git dir
        git.run(feature, "add", ".")

        tab.awaitLoaded(tab.repositoryData.worktrees) { list ->
            list.worktrees.any { info ->
                File(info.worktree.path).canonicalPath == feature.canonicalPath && info.status?.staged == 1
            }
        }
    }

    @Test
    fun `lost changes refresh everything, and watch the folders created meanwhile`(): Unit = runBlocking {
        val tab = openTab(realFileWatcher = false)
        val watcher = tab.awaitWatch(1)
        withTimeout(10.seconds) {
            while (watcher.watchedPaths.none { File(it).canonicalPath == File(main, "src").canonicalPath }) delay(10)
        }

        val created = File(main, "created").apply { mkdir() }
        File(created, "file.txt").writeText("new\n")
        git.run(main, "branch", "unreported")
        // Refreshes from the watch wait until 1.5 s after the last operation
        delay(1.6.seconds)

        watcher.events.send(WatcherEvent.ChangesLost)

        tab.awaitLoaded(tab.repositoryData.localBranches) { branches ->
            branches.any { it.name == "refs/heads/unreported" }
        }
        withTimeout(10.seconds) {
            while (watcher.watchedPaths.none { File(it).canonicalPath == created.canonicalPath }) delay(10)
        }
    }

    private suspend fun openTab(realFileWatcher: Boolean): TestTab {
        val tab = TestTab(
            testJGit(),
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
            realFileWatcher = realFileWatcher,
        ).also { tabs += it }

        tab.openRepository(main.path)
        tab.awaitLoaded(tab.repositoryData.status)

        return tab
    }

    private suspend fun TestTab.unstagedFiles(): List<String> =
        awaitLoaded(repositoryData.status).unstaged.map { it.filePath }

    private suspend fun repeatUntil(condition: suspend () -> Boolean, action: () -> Unit) {
        withTimeout(15.seconds) {
            while (true) {
                action()

                repeat(10) {
                    delay(200)
                    if (condition()) return@withTimeout
                }
            }
        }
    }
}
