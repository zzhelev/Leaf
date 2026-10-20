// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.log.parentLinks
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.usecases.LoadLogResult
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A tab loads the log further until a commit is in it (fork-only, upstream issue 270), for the commit details to select
 * a parent that is older than the commits the log loaded so far. The log starts with 2,000 commits and grows by a page
 * of 500 when scrolled to its end. The repositories here are made with `git fast-import`, which takes a moment for
 * thousands of commits where `git commit` would take half a minute.
 */
@DisabledOnOs(OS.WINDOWS)
// A search that never ends, as one without its limit, must fail a test rather than hang the build. It spins without
// ever suspending, so only a test on a thread of its own can be given up on
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LoadingLogUntilCommitTest {
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
    fun isolateGit() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config").apply { mkdirs() }, originalReader))
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a commit that the log has is found without loading anything`(): Unit = runBlocking {
        val history = importHistory { linear("main", 2_600) }
        val log = openLog()

        assertEquals(INITIAL_LOG_SIZE, log.size)

        val result = tab.loadLogUntilCommit(history.hash("main", 5))

        assertEquals(LoadLogResult.Found, result)
        assertEquals(INITIAL_LOG_SIZE, loadedLog().size)
    }

    @Test
    fun `a commit just past the first page is found by loading one page`(): Unit = runBlocking {
        val history = importHistory { linear("main", 2_600) }
        openLog()

        val result = tab.loadLogUntilCommit(history.hash("main", 2_100))

        assertEquals(LoadLogResult.Found, result)
        val log = loadedLog()
        assertTrue(log.containsKey(history.hash("main", 2_100)))
        // One page, not the whole history
        assertTrue(log.size <= INITIAL_LOG_SIZE + PAGE + 1, "loaded ${log.size} commits")
    }

    @Test
    fun `a commit several pages away is found by loading in steps that grow`(): Unit = runBlocking {
        val history = importHistory { linear("main", 5_200) }
        openLog()
        val progress = mutableListOf<Int>()

        val result = tab.loadLogUntilCommit(history.hash("main", 5_100), onProgress = { progress += it })

        assertEquals(LoadLogResult.Found, result)
        assertTrue(loadedLog().containsKey(history.hash("main", 5_100)))
        assertTrue(progress.size >= 2, "progress $progress")
        assertEquals(progress.sorted(), progress, "each step loads more than the one before")
        assertTrue(progress.zipWithNext().all { (a, b) -> b - a > a }, "each step is larger than the last: $progress")
    }

    @Test
    fun `a merge whose branches are both long has one parent that is far down the log, and it is found`(): Unit = runBlocking {
        // The log keeps a merged branch's commits together, so which parent comes first depends on the walk: with two
        // long branches, one of them is past the first page either way
        importHistory {
            val base = linear("main", 10).last()
            val mainTip = (1..2_100).fold(base) { previous, i -> commit("main", "main work $i", previous) }
            val sideTip = (1..2_100).fold(base) { previous, i -> commit("side", "side work $i", previous) }
            names["merge"] = commit("main", "Merge side", mainTip, sideTip)
        }
        val log = openLog()
        val merge = log.values.first().commit

        val before = parentLinks(merge, isStash = false) { log[it]?.commit }

        assertEquals(2, before.shown.size)
        assertEquals(1, before.shown.count { !it.isLoaded }, "one parent is past the first page: ${before.shown}")

        val far = before.shown.first { !it.isLoaded }

        assertEquals(LoadLogResult.Found, tab.loadLogUntilCommit(far.hash))

        val after = loadedLog()
        val links = parentLinks(merge, isStash = false) { after[it]?.commit }

        assertTrue(links.shown.all { it.isLoaded })
        assertTrue(links.shown.all { it.subject?.endsWith(" work 2100") == true }, "${links.shown.map { it.subject }}")
    }

    @Test
    fun `the parent of the last commit the log loaded is found, as a click at the end of the log needs`(): Unit = runBlocking {
        importHistory { linear("main", 2_600) }
        val log = openLog()
        val last = log.values.last().commit

        val before = parentLinks(last, isStash = false) { log[it]?.commit }

        assertFalse(before.shown.single().isLoaded)
        assertEquals(LoadLogResult.Found, tab.loadLogUntilCommit(last.parentsHashes.single()))
        assertEquals("main ${2_600 - INITIAL_LOG_SIZE}", loadedLog()[last.parentsHashes.single()]?.commit?.shortMessage)
    }

    @Test
    fun `a commit that no branch, tag or stash reaches ends the search at the end of the log`(): Unit = runBlocking {
        val history = importHistory {
            linear("main", 2_600)
            names["dangling"] = commit("tmp", "Nothing points here")
        }
        git.run(repository, "update-ref", "-d", "refs/heads/tmp")
        openLog()

        val dangling = tab.loadLogUntilCommit(history.hashOf("dangling"))
        val made = tab.loadLogUntilCommit("0123456789abcdef0123456789abcdef01234567")

        assertEquals(LoadLogResult.NotReachable, dangling)
        assertEquals(LoadLogResult.NotReachable, made)
        assertEquals(2_600, loadedLog().size, "the whole history is loaded, and no more")
    }

    @Test
    fun `a search stops once it loaded as many commits as it may`(): Unit = runBlocking {
        val history = importHistory { linear("main", 5_200) }
        openLog()

        val result = tab.loadLogUntilCommit(history.hash("main", 5_100), maxCommits = 1_000)

        assertInstanceOf(LoadLogResult.LimitReached::class.java, result)
        val loaded = (result as LoadLogResult.LimitReached).loaded
        // A walk takes one commit more than its limit when there is more log
        assertTrue(loaded in 1_000..1_001, "loaded $loaded")
        assertEquals(INITIAL_LOG_SIZE + loaded, loadedLog().size)
        assertFalse(loadedLog().containsKey(history.hash("main", 5_100)))
    }

    @Test
    fun `a search that is cancelled stops when its step ends, and the log is still usable`(): Unit = runBlocking {
        val history = importHistory { linear("main", 5_200) }
        openLog()
        var steps = 0

        val job = launch {
            tab.loadLogUntilCommit(history.hash("main", 5_100)) {
                steps++
                coroutineContext.job.cancel()
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, steps, "no step started after the cancellation")
        assertFalse(loadedLog().containsKey(history.hash("main", 5_100)))

        // A new search goes on from what is loaded
        assertEquals(LoadLogResult.Found, tab.loadLogUntilCommit(history.hash("main", 5_100)))
    }

    private suspend fun openLog(): GraphCommits {
        tab.openRepository(repository.path)
        return tab.awaitLoaded(tab.repositoryData.log)
    }

    private fun loadedLog(): GraphCommits =
        (tab.repositoryData.log.value as DataState.Loaded).data

    /** Makes the repository from what [build] says, with `git fast-import`, and returns where its commits are. */
    private fun importHistory(build: History.() -> Unit): ImportedHistory {
        val history = History().apply(build)

        repository.mkdirs()
        git.run(repository, "init")
        val marks = File(tempDir, "marks")
        git.runWithInput(
            repository,
            history.stream(),
            "fast-import", "--quiet", "--force", "--export-marks=${marks.path}",
        )
        git.run(repository, "checkout", "-q", "-f", "main")

        val hashByMark = marks.readLines().associate { line ->
            val (mark, hash) = line.split(' ')
            mark.removePrefix(":").toInt() to hash
        }

        return ImportedHistory(history, hashByMark)
    }

    private class ImportedHistory(private val history: History, private val hashByMark: Map<Int, String>) {
        /** The commit [depth] commits below the tip of [branch], which is the commit made with that many after it. */
        fun hash(branch: String, depth: Int): String =
            hashByMark.getValue(history.branches.getValue(branch)[history.branches.getValue(branch).size - 1 - depth])

        fun hashOf(name: String): String = hashByMark.getValue(history.names.getValue(name))
    }

    /** The text that `git fast-import` reads: commits with parents, in the order of their times. */
    private class History {
        private val out = StringBuilder()
        private var mark = 0
        private var time = 1_700_000_000L
        val names = mutableMapOf<String, Int>()

        /** The marks of each branch's commits, oldest first. */
        val branches = mutableMapOf<String, MutableList<Int>>()

        fun stream() = out.toString()

        /** Adds a commit to [branch] on top of [parents], or on top of the branch's last commit with none. */
        fun commit(branch: String, message: String, vararg parents: Int): Int {
            mark++
            time += 60

            val ref = "refs/heads/$branch"
            val actualParents = parents.toList().ifEmpty { listOfNotNull(branches[branch]?.lastOrNull()) }

            out.append("commit $ref\nmark :$mark\n")
            out.append("author Leaf Test <test@example.invalid> $time +0000\n")
            out.append("committer Leaf Test <test@example.invalid> $time +0000\n")
            out.append("data ${message.length}\n$message\n")

            if (actualParents.isNotEmpty()) {
                out.append("from :${actualParents.first()}\n")
                actualParents.drop(1).forEach { out.append("merge :$it\n") }
            }

            out.append("M 644 inline file.txt\ndata ${mark.toString().length}\n$mark\n\n")
            branches.getOrPut(branch) { mutableListOf() }.add(mark)

            return mark
        }

        /** [count] commits in a row on [branch], and their marks. */
        fun linear(branch: String, count: Int): List<Int> = List(count) { commit(branch, "$branch ${it + 1}") }
    }

    private companion object {
        /** The log's first load: 2,000 commits, and the one more that its walk takes. */
        const val INITIAL_LOG_SIZE = 2_001
        const val PAGE = 500
    }
}
