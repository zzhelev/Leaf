// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.log.headCommitHash
import dev.app.leaf.domain.models.FileChanged
import dev.app.leaf.domain.models.FileType
import dev.app.leaf.domain.models.WatcherEvent
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The log starts from the commits that other worktrees have detached (fork-only). No ref points to a commit that an
 * agent made on a detached HEAD, so the log used to leave it out, and `git rev-list` over the refs is the oracle for
 * everything else: the log must be what the refs give, plus those commits. Each test checks that its commit is on no
 * branch, tag or remote, so that it is there only because of the worktree's HEAD.
 */
@DisabledOnOs(OS.WINDOWS)
class OtherWorktreeHeadsInLogTest {
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

    @BeforeEach
    fun isolateGit() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n[init]\n\tdefaultBranch = main\n")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    /** `main` with two commits. */
    private fun createHistory(): List<String> {
        git.initRepository(main)
        git.run(main, "commit", "--allow-empty", "-m", "Second")

        return git.run(main, "rev-list", "--reverse", "HEAD").lines().filter { it.isNotBlank() }
    }

    private fun head(directory: File = main) = git.run(directory, "rev-parse", "HEAD").trim()

    private fun detachedWorktree(name: String, commits: Int = 1, repository: File = main): File {
        val worktree = File(tempDir, name)
        git.run(repository, "worktree", "add", "--detach", worktree.path)
        repeat(commits) { git.run(worktree, "commit", "--allow-empty", "-m", "$name work $it") }

        return worktree
    }

    /** That no branch, tag or remote branch has [commit], so that only a HEAD can bring it into the log. */
    private fun assertOnNoRef(commit: String) {
        assertEquals("", git.run(main, "for-each-ref", "--contains", commit, "refs/heads", "refs/tags", "refs/remotes"))
    }

    /** What the log must have: the commits of the refs, and of the HEADs in [heads]. */
    private fun expectedLog(vararg heads: String): Set<String> =
        git.run(main, "rev-list", "--branches", "--tags", "--remotes", *heads).lines().filter { it.isNotBlank() }.toSet()

    private suspend fun openTab(waitFor: String? = null): Set<String> {
        tab.openRepository(main.path)

        return loggedCommits(waitFor)
    }

    /** The hashes of the log's rows, once it has [waitFor]. */
    private suspend fun loggedCommits(waitFor: String? = null): Set<String> =
        tab.awaitLoaded(tab.repositoryData.log) { rows -> waitFor == null || rows.values.any { it.hash == waitFor } }
            .keys
            .toSet()

    @Test
    fun `the commits of detached worktrees are in the log, with what they stand on`(): Unit = runBlocking {
        val (first, second) = createHistory()
        val agent1 = detachedWorktree("agent1")
        val agent2 = detachedWorktree("agent2", commits = 3)
        assertOnNoRef(head(agent1))
        assertOnNoRef(head(agent2))

        val log = openTab(waitFor = head(agent2))

        assertEquals(expectedLog(head(agent1), head(agent2)), log)
        assertEquals(setOf(first, second, head(agent1), head(agent2)), log.intersect(setOf(first, second, head(agent1), head(agent2))))
        // main's two commits, one for agent1 and three for agent2
        assertEquals(6, log.size)
    }

    @Test
    fun `a rebase stopped at a conflict in another worktree brings its commit`(): Unit = runBlocking {
        git.initRepository(main)
        File(main, "a.txt").writeText("base\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Add a")
        val agent = File(tempDir, "agent")
        git.run(main, "worktree", "add", "-b", "feature", agent.path)
        File(agent, "b.txt").writeText("b\n")
        git.run(agent, "add", ".")
        git.run(agent, "commit", "-m", "Feature adds b")
        File(agent, "a.txt").writeText("feature\n")
        git.run(agent, "commit", "-am", "Feature changes a")
        File(main, "a.txt").writeText("main\n")
        git.run(main, "commit", "-am", "Main changes a")
        git.runFailing(agent, "rebase", "main")
        val during = head(agent)
        assertOnNoRef(during)

        val log = openTab(waitFor = during)

        assertTrue(during in log)
        assertEquals(expectedLog(during), log)
    }

    @Test
    fun `a bisect in another worktree brings the commit it checked out`(): Unit = runBlocking {
        createHistory()
        val agent = detachedWorktree("agent", commits = 4)
        val first = git.run(agent, "rev-parse", "HEAD~3").trim()
        git.run(agent, "bisect", "start", "HEAD", first)
        val midway = head(agent)
        assertNotEquals(first, midway)
        assertOnNoRef(midway)

        val log = openTab(waitFor = midway)

        assertEquals(expectedLog(midway), log)
    }

    @Test
    fun `a linked worktree's tab has the main worktree's detached commit`(): Unit = runBlocking {
        createHistory()
        git.run(main, "checkout", "--detach")
        git.run(main, "commit", "--allow-empty", "-m", "Detached in main")
        val mainHead = head(main)
        val feature = File(tempDir, "feature")
        git.run(main, "worktree", "add", "-b", "feature", feature.path, "main")
        assertOnNoRef(mainHead)

        openTab(waitFor = mainHead)
        tab.switchWorktree(feature.path).join()

        val inFeature = loggedCommits(waitFor = mainHead)
        val current = tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.name == "refs/heads/feature" }

        assertEquals("refs/heads/feature", current?.name)
        assertEquals(expectedLog(mainHead), inFeature)
    }

    @Test
    fun `a worktree on a branch changes nothing`(): Unit = runBlocking {
        createHistory()
        val feature = File(tempDir, "feature")
        git.run(main, "worktree", "add", "-b", "feature", feature.path)
        git.run(feature, "commit", "--allow-empty", "-m", "Feature work")

        val log = openTab(waitFor = head(feature))

        // The branch has it, as before
        assertEquals(expectedLog(), log)
        assertEquals(3, log.size)
    }

    @Test
    fun `a worktree whose folder is gone is skipped, with the log still loading`(): Unit = runBlocking {
        val (first, second) = createHistory()
        val gone = detachedWorktree("gone")
        val kept = detachedWorktree("kept")
        val lockedGone = detachedWorktree("locked-gone")
        git.run(main, "worktree", "lock", lockedGone.path)
        val goneHead = head(gone)
        val lockedHead = head(lockedGone)
        openTab(waitFor = head(kept))
        assertTrue(goneHead in loggedCommits())
        assertTrue(lockedHead in loggedCommits())

        FileUtils.delete(gone, FileUtils.RECURSIVE)
        FileUtils.delete(lockedGone, FileUtils.RECURSIVE)
        tab.refreshData(DataToRefresh.ALL).join()
        val log = tab.awaitLoaded(tab.repositoryData.log) { goneHead !in it.keys }.keys.toSet()

        assertEquals(setOf(first, second, head(kept)), log)
        assertFalse(lockedHead in log)
    }

    @Test
    fun `the tab's own HEAD is marked once, and the other worktree's commit has no chip`(): Unit = runBlocking {
        val (_, second) = createHistory()
        git.run(main, "checkout", "--detach")
        git.run(main, "commit", "--allow-empty", "-m", "Detached in the tab")
        val own = head(main)
        val agent = detachedWorktree("agent")
        val others = head(agent)
        assertOnNoRef(own)
        assertOnNoRef(others)

        val log = openTab(waitFor = others)
        val current = tab.awaitLoaded(tab.repositoryData.currentBranch) { it?.hash == own }
        val branches = tab.awaitLoaded(tab.repositoryData.localBranches)
        val tags = tab.awaitLoaded(tab.repositoryData.tags)

        assertEquals("HEAD", current?.name)
        assertEquals(own, headCommitHash(current))
        assertEquals(1, tab.awaitLoaded(tab.repositoryData.log).values.count { it.hash == headCommitHash(current) })
        // The refs are what they were: main, and the tab's detached HEAD
        assertEquals(setOf("refs/heads/main", "HEAD"), branches.map { it.name }.toSet())
        assertEquals(emptyList<String>(), tags.map { it.name })
        assertTrue(branches.none { it.hash == others }, "no chip for another worktree's commit")
        assertEquals(expectedLog(own, others), log)
        assertTrue(second in log)
    }

    @Test
    fun `twenty detached worktrees all have their commits in the log`(): Unit = runBlocking {
        createHistory()
        val agents = (1..20).map { detachedWorktree("agent$it") }
        val heads = agents.map(::head)

        val log = openTab(waitFor = heads.last())

        assertEquals(expectedLog(*heads.toTypedArray()), log)
        assertEquals(2 + 20, log.size)
    }

    @Test
    fun `a commit made later in another worktree shows once the watcher reports its HEAD, not before`(): Unit =
        runBlocking {
            createHistory()
            val agent = detachedWorktree("agent")
            openTab(waitFor = head(agent))
            val watcher = tab.awaitWatch(1)

            git.run(agent, "commit", "--allow-empty", "-m", "More work")
            val more = head(agent)
            assertOnNoRef(more)

            // What git also touches in the worktree's git dir: the index and the message of the commit
            watcher.report("worktrees/agent/index", "worktrees/agent/COMMIT_EDITMSG", "worktrees/agent/logs/HEAD")
            delay(3_000)
            assertFalse(more in loggedCommits(), "an index change alone refreshed the log")

            watcher.report("worktrees/agent/HEAD")
            val log = loggedCommits(waitFor = more)

            assertEquals(expectedLog(more), log)
        }

    @Test
    fun `a worktree removed leaves the log when the watcher reports its files deleted`(): Unit = runBlocking {
        createHistory()
        val agent = detachedWorktree("agent")
        val agentHead = head(agent)
        val keeper = detachedWorktree("keeper")
        openTab(waitFor = head(keeper))
        val watcher = tab.awaitWatch(1)
        assertTrue(agentHead in loggedCommits())

        git.run(main, "worktree", "remove", agent.path)
        watcher.report("worktrees/agent", "worktrees/agent/HEAD", "worktrees/agent/index")
        val log = tab.awaitLoaded(tab.repositoryData.log) { agentHead !in it.keys }.keys.toSet()

        assertEquals(expectedLog(head(keeper)), log)
    }

    @Test
    fun `a worktree added shows its commit when the watcher reports it`(): Unit = runBlocking {
        createHistory()
        openTab()
        val watcher = tab.awaitWatch(1)

        val agent = detachedWorktree("agent", commits = 2)
        // A new folder: on Linux, only its own creation may be reported
        watcher.report("worktrees/agent", directory = true)

        assertEquals(expectedLog(head(agent)), loggedCommits(waitFor = head(agent)))
    }

    @Test
    fun `an agent rewriting its index refreshes the worktrees, and never the log`(): Unit = runBlocking {
        createHistory()
        val agent = detachedWorktree("agent")
        openTab(waitFor = head(agent))
        val watcher = tab.awaitWatch(1)
        val triggers = CopyOnWriteArrayList<List<DataToRefresh>>()
        val collecting = launch(Dispatchers.Default) { tab.repositoryState.refreshTriggered.collect { triggers += it } }
        delay(200)

        // Three hundred writes in a second and a half, as git status in a loop does
        repeat(300) {
            watcher.report("worktrees/agent/index")
            delay(5)
        }
        delay(1_000)
        collecting.cancelAndJoin()

        assertEquals(emptyList<List<DataToRefresh>>(), triggers.toList())
    }

    @Test
    fun `a storm of HEAD moves refreshes the log once per gap`(): Unit = runBlocking {
        createHistory()
        val agent = detachedWorktree("agent")
        openTab(waitFor = head(agent))
        val watcher = tab.awaitWatch(1)
        val triggers = CopyOnWriteArrayList<List<DataToRefresh>>()
        val collecting = launch(Dispatchers.Default) { tab.repositoryState.refreshTriggered.collect { triggers += it } }
        delay(200)

        val start = System.nanoTime()
        repeat(300) {
            watcher.report("worktrees/agent/HEAD", "worktrees/agent/index")
            delay(5)
        }
        // The one that waited for the gap
        delay(3_000)
        collecting.cancelAndJoin()
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0

        val logRefreshes = triggers.count { DataToRefresh.LOG in it }

        // The first right away, then no more than one for each 2 s gap, and nothing else
        assertTrue(logRefreshes >= 2, "the HEADs that moved meanwhile were refreshed once: $triggers")
        assertTrue(logRefreshes <= seconds / 1.9 + 1, "$logRefreshes refreshes in $seconds s")
        assertEquals(emptyList<List<DataToRefresh>>(), triggers.filter { it != listOf(DataToRefresh.LOG) })
    }

    /**
     * Sends a batch of changes at [paths] in the common git dir, as the watcher does. On macOS it reports real paths
     * (`/private/var/...` for `/var/...`), and the tab matches those against its worktrees' folder.
     */
    private suspend fun RecordingFileChangesWatcher.report(vararg paths: String, directory: Boolean = false) {
        events.send(
            WatcherEvent.ChangesDetected(
                paths.map {
                    FileChanged(File(main.canonicalFile, ".git/$it").path, if (directory) FileType.DIRECTORY else FileType.FILE)
                }
            )
        )
    }
}
