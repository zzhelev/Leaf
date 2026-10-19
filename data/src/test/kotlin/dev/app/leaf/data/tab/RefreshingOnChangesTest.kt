// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.models.FileChanged
import dev.app.leaf.domain.models.FileType
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
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
    fun `an edit in any of hundreds of folders refreshes the status`(): Unit = runBlocking {
        // macOS stops delivering events past about 250 registrations, so watching each folder left the last ones dead
        val folders = (0 until 300).map { File(main, "many/d$it").apply { mkdirs() } }
        File(main, ".gitignore").writeText("build/\n")
        File(main, "build/classes").mkdirs()
        val tab = openTab(realFileWatcher = true)

        // Each folder, one file at a time, as any edit refreshes everything and would show the files of the others
        for (index in folders.indices step 25) {
            val file = File(folders[index], "file.txt")

            repeatUntil({ tab.unstagedFiles().contains("many/d$index/file.txt") }) {
                file.writeText("edited ${System.nanoTime()}\n")
            }
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
        awaitWatching(watcher, File(main, "src"))

        val created = File(main, "created").apply { mkdir() }
        File(created, "file.txt").writeText("new\n")
        git.run(main, "branch", "unreported")
        // Refreshes from the watch wait until 1.5 s after the last operation
        delay(1.6.seconds)

        watcher.events.send(WatcherEvent.ChangesLost)

        tab.awaitLoaded(tab.repositoryData.localBranches) { branches ->
            branches.any { it.name == "refs/heads/unreported" }
        }
        awaitWatching(watcher, created)
    }

    @Test
    fun `a folder created while an operation runs is watched, and refreshes nothing`(): Unit = runBlocking {
        val tab = openTab(realFileWatcher = false)
        val watcher = tab.awaitWatch(1)
        // The working tree as the watch has it, which the watcher's paths start with
        val workingTree = File(awaitWatching(watcher, File(main, "src"))).parentFile
        val operationEnds = CompletableDeferred<Unit>()
        val operation = startOperation(tab, operationEnds)

        val created = File(workingTree, "created").apply { mkdir() }
        val file = File(created, "file.txt").apply { writeText("new\n") }
        watcher.events.send(
            WatcherEvent.ChangesDetected(
                listOf(FileChanged(created.path, FileType.DIRECTORY), FileChanged(file.path, FileType.FILE))
            )
        )

        awaitWatching(watcher, created)
        // A refresh would start right after the folder is watched
        delay(500)
        assertFalse("created/file.txt" in tab.unstagedFiles())

        operationEnds.complete(Unit)
        operation.join()
    }

    @Test
    fun `a deleted folder is no longer watched, in a batch that created none`(): Unit = runBlocking {
        val tab = openTab(realFileWatcher = false)
        val watcher = tab.awaitWatch(1)
        val src = awaitWatching(watcher, File(main, "src"))

        FileUtils.delete(File(src), FileUtils.RECURSIVE)
        watcher.events.send(
            WatcherEvent.ChangesDetected(
                listOf(
                    FileChanged(src, FileType.DIRECTORY),
                    FileChanged(File(src, "main.txt").path, FileType.FILE),
                )
            )
        )

        withTimeout(10.seconds) {
            while (src in watcher.watchedPaths) delay(10)
        }
    }

    @Test
    fun `lost changes while an operation runs watch the folders created meanwhile, and refresh nothing`(): Unit =
        runBlocking {
            // git removes the folder of the linked worktrees' git dirs with the last one
            git.run(main, "worktree", "remove", feature.path)
            val worktreesDir = File(main, ".git/worktrees")
            assertFalse(worktreesDir.exists())

            val tab = openTab(realFileWatcher = false)
            val watcher = tab.awaitWatch(1)
            awaitWatching(watcher, File(main, "src"))
            val operationEnds = CompletableDeferred<Unit>()
            val operation = startOperation(tab, operationEnds)

            val created = File(main, "created").apply { mkdir() }
            File(created, "file.txt").writeText("new\n")
            git.run(main, "worktree", "add", File(tempDir, "agent").path, "-b", "agent")

            watcher.events.send(WatcherEvent.ChangesLost)

            awaitWatching(watcher, worktreesDir)
            awaitWatching(watcher, created)
            // A refresh would start right after the folders are watched
            delay(500)
            val branches = tab.awaitLoaded(tab.repositoryData.localBranches)
            assertFalse(branches.any { it.name == "refs/heads/agent" })

            operationEnds.complete(Unit)
            operation.join()
        }

    @Test
    fun `a folder that Leaf's Ignore covers is no longer watched with its parent`(): Unit = runBlocking {
        File(main, "src/generated/deep").mkdirs()
        File(main, "src/generated/out.txt").writeText("generated\n")
        val tab = openTab(realFileWatcher = false)
        val watcher = tab.awaitWatch(1)
        val src = awaitWatching(watcher, File(main, "src"))
        // Nothing in it is ignored: one registration for it and all that is under it
        assertTrue(watcher.isWatchedRecursively(src))

        tab.addIgnoreRules(IgnorePlace.GITIGNORE, listOf("/src/generated/")).join()

        // The status that the task refreshes lists the folder as ignored
        withTimeout(10.seconds) {
            while (watcher.isWatchedRecursively(src)) delay(10)
        }
        assertTrue(watcher.watchedPaths.contains(src), "src is watched alone: ${watcher.watchedPaths}")
        assertFalse(watcher.watchedPaths.any { File(it).canonicalPath.startsWith(File(main, "src/generated").canonicalPath) })
    }

    @Test
    fun `a folder that a build makes in a folder watched as a whole is left out once the status lists it`(): Unit =
        runBlocking {
            File(main, ".gitignore").writeText("target/\n")
            val tab = openTab(realFileWatcher = false)
            val watcher = tab.awaitWatch(1)
            val src = awaitWatching(watcher, File(main, "src"))
            assertTrue(watcher.isWatchedRecursively(src))
            // The refreshes that the watch starts wait until 1.5 s after the last operation
            delay(1.6.seconds)

            val target = File(main, "src/target/classes").apply { mkdirs() }
            val workingTree = File(src).parentFile
            val file = File(target, "A.class").apply { writeText("class") }
            watcher.events.send(
                WatcherEvent.ChangesDetected(
                    listOf(
                        FileChanged(File(workingTree, "src/target").path, FileType.DIRECTORY),
                        FileChanged(file.path, FileType.FILE),
                    )
                )
            )

            withTimeout(10.seconds) {
                while (watcher.isWatchedRecursively(src)) delay(10)
            }
            assertFalse(watcher.watchedPaths.any { File(it).canonicalPath.startsWith(File(main, "src/target").canonicalPath) })
        }

    /** Runs an operation of Leaf's, such as a checkout, until [ends] completes. */
    private suspend fun CoroutineScope.startOperation(tab: TestTab, ends: CompletableDeferred<Unit>): Job {
        val operation = launch {
            tab.repositoryState.runOperation(TaskType.CheckoutBranch, isForegroundTask = true) { ends.await() }
        }
        withTimeout(10.seconds) { tab.repositoryState.currentTask.first { it != null } }

        return operation
    }

    /** Waits until [watcher] watches [folder], and returns the path that it was given. */
    private suspend fun awaitWatching(watcher: RecordingFileChangesWatcher, folder: File): String =
        withTimeout(10.seconds) {
            var path: String? = null

            while (path == null) {
                path = watcher.watchedPaths.firstOrNull { File(it).canonicalPath == folder.canonicalPath }
                if (path == null) delay(10)
            }

            path
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
