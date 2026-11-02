// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.log.walkForHits
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.log.matchesLogSearch
import dev.app.leaf.domain.models.FoundCommits
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.usecases.LoadLogResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The log's search walks the whole history, not only the commits that the log has loaded (fork-only, upstream issue
 * 277). The log starts with 2,000 commits and grows by a page of 500 when scrolled to its end, so a match further down
 * was never found. The repositories are made with `git fast-import`.
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SearchingLogTest {
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

    /** A history of [count] commits, with `needle` in the messages of those that are [needles] commits below the tip. */
    private fun importNeedles(count: Int, vararg needles: Int) = importHistory(git, repository, tempDir) {
        linear("main", count) { oldestFirst ->
            val depth = count - oldestFirst

            when (depth) {
                in needles -> "Needle at depth $depth"
                else -> "main $oldestFirst"
            }
        }
    }

    private suspend fun search(term: String): FoundCommits {
        val result = tab.searchLog(term)

        return (result as? Either.Ok)?.value ?: error("The search failed: $result")
    }

    @Test
    fun `finds matches that the log hasn't loaded, newest first, with how deep they are`(): Unit = runBlocking {
        val history = importNeedles(5_200, 50, 2_900, 5_160)
        val log = openLog()

        val found = search("needle")

        assertEquals(listOf(50, 2_900, 5_160), found.hits.map { it.depth })
        assertEquals(
            listOf(50, 2_900, 5_160).map { history.hash("main", it) },
            found.hits.map { it.hash },
        )
        assertFalse(found.reachedHitLimit)
        // The log has 2,001 commits: only the first match is among them
        assertEquals(INITIAL_LOG_SIZE, log.size)
        assertEquals(listOf(true, false, false), found.hits.map { log.containsKey(it.hash) })
    }

    @Test
    fun `a match on the last page is shown by loading the log to its depth in one step`(): Unit = runBlocking {
        val history = importNeedles(8_000, 5_160)
        openLog()

        val hit = search("needle").hits.single()

        assertEquals(5_160, hit.depth)
        assertEquals(history.hash("main", 5_160), hit.hash)

        val result = tab.loadLogUntilCommit(hit.hash, expectedDepth = hit.depth)

        assertEquals(LoadLogResult.Found, result)
        val loaded = loadedLog().size
        // Where the steps that double would have loaded 5,501 commits, after three walks
        assertTrue(loaded in 5_161..5_262, "loaded $loaded commits")
        assertTrue(loadedLog().containsKey(hit.hash))
    }

    @Test
    fun `a depth that was too small only costs the steps that double`(): Unit = runBlocking {
        val history = importNeedles(8_000, 5_160)
        openLog()

        val result = tab.loadLogUntilCommit(history.hash("main", 5_160), expectedDepth = 3_000)

        assertEquals(LoadLogResult.Found, result)
        assertTrue(loadedLog().containsKey(history.hash("main", 5_160)))
    }

    @Test
    fun `a commit that the log has is found without loading anything, whatever the depth`(): Unit = runBlocking {
        val history = importNeedles(5_200, 50)
        openLog()

        val result = tab.loadLogUntilCommit(history.hash("main", 50), expectedDepth = 4_000)

        assertEquals(LoadLogResult.Found, result)
        assertEquals(INITIAL_LOG_SIZE, loadedLog().size)
    }

    @Test
    fun `matches the message, the author's and the committer's name and the hash, ignoring case`(): Unit = runBlocking {
        val history = importHistory(git, repository, tempDir) {
            names["message"] = commit("main", "Fix the PARSER bug")
            names["author"] = commit("main", "Tidy up", author = "Ada Lovelace")
            names["committer"] = commit("main", "Tidy up again", author = "Leaf Test", committer = "Grace Hopper")
            names["other"] = commit("main", "Nothing to see")
        }
        openLog()

        suspend fun hashes(term: String) = search(term).hits.map { it.hash }

        assertEquals(listOf(history.hashOf("message")), hashes("parser"))
        assertEquals(listOf(history.hashOf("author")), hashes("LOVELACE"))
        assertEquals(listOf(history.hashOf("committer")), hashes("grace hopper"))

        // Anywhere in the hash, not only at its start
        val middle = history.hashOf("other").substring(12, 20)

        assertTrue(history.hashOf("other") in hashes(middle))
        assertEquals(emptyList<String>(), hashes("zzz-nobody-wrote-this"))
    }

    @Test
    fun `finds the commits that the filter of the loaded commits finds, and more`(): Unit = runBlocking {
        importNeedles(3_000, 10, 20, 2_500)
        val log = openLog()

        for (term in listOf("needle", "main 17", "leaf test", "depth 20", "zzz")) {
            val lowercase = term.lowercase()
            val inLog = log.values.filter { it.commit.matchesLogSearch(lowercase) }.map { it.hash }

            val found = search(term).hits

            // What the loaded commits have, in the same order, and what is past them after it
            assertEquals(inLog, found.filter { log.containsKey(it.hash) }.map { it.hash }, "for '$term'")
            assertEquals(found.sortedBy { it.depth }, found, "for '$term'")
        }
    }

    @Test
    fun `a hit's depth is its place in the log, also past merges`(): Unit = runBlocking {
        importHistory(git, repository, tempDir) {
            val base = linear("main", 5).last()
            val mainTip = (1..40).fold(base) { previous, i -> commit("main", "main work $i", previous) }
            val sideTip = (1..40).fold(base) { previous, i -> commit("side", "side work $i", previous) }
            names["merge"] = commit("main", "Merge side", mainTip, sideTip)
            (1..10).fold(names.getValue("merge")) { previous, i -> commit("main", "after the merge $i", previous) }
        }
        val log = openLog()
        val rows = log.keys.toList()

        val hits = search("work").hits

        assertEquals(80, hits.size)
        assertEquals(hits.map { rows.indexOf(it.hash) }, hits.map { it.depth })
    }

    @Test
    fun `stops at as many hits as it may`(): Unit = runBlocking {
        importNeedles(3_000)
        openLog()

        val result = tab.findCommits(
            repository.resolve(".git").path,
            startingCommits = listOf(git.run(repository, "rev-parse", "HEAD").trim()),
            stashes = emptySet(),
            lowercaseTerm = "main",
            maxHits = 25,
        )

        val found = (result as Either.Ok).value

        assertEquals(25, found.hits.size)
        assertTrue(found.reachedHitLimit)
        assertEquals((0 until 25).toList(), found.hits.map { it.depth })
    }

    @Test
    fun `stops after as many commits as it may`(): Unit = runBlocking {
        importNeedles(3_000, 100, 2_000)
        openLog()

        val result = tab.findCommits(
            repository.resolve(".git").path,
            startingCommits = listOf(git.run(repository, "rev-parse", "HEAD").trim()),
            stashes = emptySet(),
            lowercaseTerm = "needle",
            maxCommits = 1_000,
        )

        assertEquals(listOf(100), (result as Either.Ok).value.hits.map { it.depth })
    }

    @Test
    fun `a stash's other parents, which the log leaves out, are no hits`(): Unit = runBlocking {
        importHistory(git, repository, tempDir) { linear("main", 6) }
        git.run(repository, "config", "user.name", "Leaf Test")
        git.run(repository, "config", "user.email", "test@example.invalid")
        File(repository, "file.txt").writeText("changed")
        git.run(repository, "stash", "push", "--message", "set aside")
        val indexCommit = git.run(repository, "rev-parse", "stash@{0}^2").trim()
        val stash = git.run(repository, "rev-parse", "stash@{0}").trim()
        val log = openLog()

        assertFalse(log.containsKey(indexCommit), "the log skips the stash's index commit")

        val found = search("main").hits
        val hits = found.map { it.hash }

        // The stash's message is "On main: set aside" and its index commit's "index on main: ..."
        assertTrue(stash in hits)
        assertFalse(indexCommit in hits)
        assertTrue(hits.all { log.containsKey(it) }, "every hit is a row of the log")
        assertEquals(hits.map { log.keys.indexOf(it) }, found.map { it.depth })
    }

    @Test
    fun `a walk that is cancelled stops at the next commit`(): Unit = runBlocking {
        importNeedles(3_000)
        var matched = 0

        val job = launch(Dispatchers.IO) {
            val context = coroutineContext

            Git.open(repository).use { git ->
                walkForHits(
                    context, git.repository,
                    startingCommits = listOf(git.repository.resolve("HEAD").name),
                    stashes = emptySet(),
                    maxHits = 10_000,
                    maxCommits = 200_000,
                ) {
                    matched++

                    if (matched == 100) context.job.cancel()

                    true
                }
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(100, matched, "no commit was looked at after the cancellation")
    }

    @Test
    fun `a search that is cancelled before it starts finds nothing and leaves the tab usable`(): Unit = runBlocking {
        importNeedles(3_000, 100)
        openLog()
        var result: Any? = null

        val job = launch(Dispatchers.IO) {
            coroutineContext.job.cancel()

            result = tab.searchLog("needle")
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(null, result)
        assertEquals(listOf(100), search("needle").hits.map { it.depth })
    }

    private suspend fun openLog(): GraphCommits {
        tab.openRepository(repository.path)
        return tab.awaitLoaded(tab.repositoryData.log)
    }

    private fun loadedLog(): GraphCommits =
        (tab.repositoryData.log.value as DataState.Loaded).data

    private companion object {
        /** The log's first load: 2,000 commits, and the one more that its walk takes. */
        const val INITIAL_LOG_SIZE = 2_001
    }
}
