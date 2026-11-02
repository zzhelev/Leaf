// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.log.SearchStep
import dev.app.leaf.domain.log.matchesLogSearch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.FoundCommits
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.SearchHit
import dev.app.leaf.domain.usecases.LoadLogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private fun commit(depth: Int, message: String): GraphCommit = GraphCommit(
    commit = Commit(
        hash = "a%07d".format(depth),
        message = message,
        committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        date = 0L,
        parentsHashes = emptyList(),
    ),
    lane = 0,
    passingLanes = emptyList(),
    mergingLanes = emptyList(),
    forkingOffLanes = emptyList(),
    childCount = 1,
)

/**
 * The log's search of the whole history (fork-only, upstream issue 277), with a history of [total] commits of which the
 * log has the first [loadedCount], and fakes for the walk and the loading of the log. The commits at [needles] (their
 * depth, newest first) have `needle` in their message.
 *
 * It runs in the test's own `runBlocking`, so that nothing it started can outlive the test: one that did would hang.
 */
@Timeout(30)
class LogSearchControllerTest {
    private class World(
        val scope: CoroutineScope,
        needles: Set<Int>,
        loadedCount: Int,
        total: Int = 60,
        debounce: Duration = Duration.ZERO,
    ) {
        val history = List(total) { commit(it, if (it in needles) "needle at $it" else "plain $it") }
        val loaded = LinkedHashMap<String, GraphCommit>().apply { history.take(loadedCount).forEach { put(it.hash, it) } }
        val results = MutableStateFlow<LogSearch>(LogSearch.NotSearching)

        /** The commits that the log scrolled to, in order. */
        val focused = mutableListOf<String>()

        /** The commits that the log was asked to load until, with the depth that the search expected. */
        val loads = mutableListOf<Pair<String, Int>>()

        /** The terms that the walk was started for. */
        val walks = mutableListOf<String>()
        var walksCancelled = 0

        /** While it is set, a walk or a load waits for it. */
        var walkGate: CompletableDeferred<Unit>? = null
        var loadGate: CompletableDeferred<Unit>? = null

        var walkResult: FoundCommits? = null
        var walkFails = false

        /** As `JGit.provide` does: a cancelled walk comes back as a failure, not as a cancellation. */
        var walkSwallowsCancellation = false
        var unreachable = emptySet<String>()

        val controller = LogSearchController(
            scope = scope,
            results = results,
            loadedCommits = { GraphCommits(loaded) },
            searchHistory = ::walk,
            loadLogUntilCommit = ::load,
            focus = { focused += it.hash },
            debounce = debounce,
        )

        fun hash(depth: Int) = history[depth].hash

        val search get() = results.value as LogSearch.SearchResults

        private suspend fun walk(term: String): FoundCommits? {
            walks += term

            try {
                walkGate?.await()
            } catch (e: CancellationException) {
                walksCancelled++

                if (walkSwallowsCancellation) {
                    // The walk goes on for a while after the cancellation, as JGit's does, before it comes back as a failure
                    withContext(NonCancellable) { repeat(20) { yield() } }

                    return null
                }

                throw e
            }

            if (walkFails) return null

            return walkResult ?: FoundCommits(
                history.mapIndexedNotNull { depth, c -> SearchHit(c.hash, depth).takeIf { c.commit.matchesLogSearch(term) } },
                reachedHitLimit = false,
            )
        }

        private suspend fun load(hash: String, depth: Int): LoadLogResult {
            loads += hash to depth
            loadGate?.await()

            if (hash in unreachable || history.none { it.hash == hash }) return LoadLogResult.NotReachable

            history.take(depth + 100).forEach { loaded.putIfAbsent(it.hash, it) }

            return LoadLogResult.Found
        }
    }

    /** Lets what the controller started run until it waits for something. */
    private suspend fun settle() = repeat(40) { yield() }

    private fun runWorld(
        needles: Set<Int>,
        loadedCount: Int,
        total: Int = 60,
        debounce: Duration = Duration.ZERO,
        block: suspend World.() -> Unit,
    ): Unit = runBlocking { World(this, needles, loadedCount, total, debounce).block() }

    @Test
    fun `the loaded commits answer at once, with a plus for the walk that is still running`() = runWorld(
        needles = setOf(2, 5, 30, 50), loadedCount = 10,
    ) {
        walkGate = CompletableDeferred()

        controller.search("Needle")
        settle()

        assertEquals(listOf(hash(2), hash(5)), search.hits.list.map { it.hash })
        assertEquals(1, search.index)
        assertTrue(search.isSearching)
        assertEquals("2+", search.totalText)
        assertEquals(listOf(hash(2)), focused, "the first match scrolls into view, as it always did")

        walkGate!!.complete(Unit)
        settle()
    }

    @Test
    fun `the hits of the whole history replace them, and the counter keeps its commit`() = runWorld(
        needles = setOf(2, 5, 30, 50), loadedCount = 10,
    ) {
        walkGate = CompletableDeferred()

        controller.search("needle")
        settle()
        controller.step(SearchStep.NEXT)
        assertEquals(hash(5), search.focusedHit?.hash)

        walkGate!!.complete(Unit)
        settle()

        assertEquals(listOf(2, 5, 30, 50), search.hits.list.map { it.depth })
        assertEquals(2, search.index, "still the commit that it pointed at")
        assertFalse(search.isSearching)
        assertEquals("4", search.totalText)
        assertEquals(listOf(hash(2), hash(5)), focused, "the walk's end scrolls nowhere")
        assertEquals(emptyList<Pair<String, Int>>(), loads)
    }

    @Test
    fun `a term that only matches commits the log hasn't loaded opens the first one, loading the log to it`() = runWorld(
        needles = setOf(30, 50), loadedCount = 10,
    ) {
        walkGate = CompletableDeferred()

        controller.search("needle")
        settle()

        assertEquals(0, search.index)
        assertEquals("…", search.totalText, "nothing is found yet, and the walk is running")
        assertEquals(emptyList<String>(), focused)

        walkGate!!.complete(Unit)
        settle()

        assertEquals(listOf(30, 50), search.hits.list.map { it.depth })
        assertEquals(1, search.index)
        assertEquals(listOf(hash(30) to 30), loads, "one load, to the depth the walk found")
        assertEquals(listOf(hash(30)), focused)
        assertTrue(loaded.containsKey(hash(30)))
    }

    @Test
    fun `a step to a hit that isn't loaded loads the log to it first, and one that is loaded doesn't`() = runWorld(
        needles = setOf(2, 5, 30), loadedCount = 10,
    ) {
        controller.search("needle")
        settle()
        assertEquals(listOf(hash(2)), focused)

        controller.step(SearchStep.NEXT)
        assertEquals(listOf(hash(2), hash(5)), focused)
        assertEquals(emptyList<Pair<String, Int>>(), loads)

        controller.step(SearchStep.NEXT)
        assertEquals(3, search.index)
        assertEquals(listOf(hash(30) to 30), loads)
        assertEquals(hash(30), focused.last())
    }

    @Test
    fun `stepping wraps at both ends, scrolling each time`() = runWorld(
        needles = setOf(2, 5, 7), loadedCount = 10,
    ) {
        controller.search("needle")
        settle()
        assertEquals(1, search.index)

        controller.step(SearchStep.PREVIOUS)
        assertEquals(3, search.index, "previous from the first goes to the last")

        controller.step(SearchStep.NEXT)
        assertEquals(1, search.index, "next from the last goes to the first")

        controller.step(SearchStep.NEXT)
        controller.step(SearchStep.NEXT)
        controller.step(SearchStep.NEXT)
        assertEquals(1, search.index, "three more steps go round once")
        assertEquals(listOf(2, 7, 2, 5, 7, 2).map { hash(it) }, focused)
    }

    @Test
    fun `stepping wraps to a hit past the loaded commits and loads the log to it`() = runWorld(
        needles = setOf(2, 5, 50), loadedCount = 10,
    ) {
        controller.search("needle")
        settle()

        controller.step(SearchStep.PREVIOUS)

        assertEquals(3, search.index)
        assertEquals(listOf(hash(50) to 50), loads)
        assertEquals(hash(50), focused.last())
    }

    @Test
    fun `a single hit is scrolled to again by a step`() = runWorld(needles = setOf(4), loadedCount = 10) {
        controller.search("needle")
        settle()
        controller.step(SearchStep.NEXT)

        assertEquals(1, search.index)
        assertEquals(listOf(hash(4), hash(4)), focused)
    }

    @Test
    fun `stepping with no hits does nothing`() = runWorld(needles = emptySet(), loadedCount = 10) {
        controller.search("needle")
        settle()
        controller.step(SearchStep.NEXT)
        controller.step(SearchStep.PREVIOUS)

        assertEquals(0, search.index)
        assertEquals("0", search.totalText)
        assertEquals(emptyList<String>(), focused)
    }

    @Test
    fun `a new term cancels the walk of the one before, whose answer is never shown`() = runWorld(
        needles = setOf(2, 30), loadedCount = 10,
    ) {
        walkGate = CompletableDeferred()

        controller.search("needle")
        settle()
        assertEquals(listOf("needle"), walks)

        controller.search("plain")
        settle()

        assertEquals(1, walksCancelled, "the first walk was stopped")
        assertEquals(listOf("needle", "plain"), walks)

        walkGate!!.complete(Unit)
        settle()

        assertTrue(search.hits.list.isNotEmpty())
        assertTrue(search.hits.list.all { history[it.depth].message.startsWith("plain") }, "only the last term's hits")
    }

    @Test
    fun `a walk that comes back as a failure when it is cancelled changes nothing of the newer search`() = runWorld(
        needles = setOf(2, 30), loadedCount = 10,
    ) {
        walkSwallowsCancellation = true
        walkGate = CompletableDeferred()

        controller.search("needle")
        settle()
        controller.search("plain")
        settle()

        assertEquals(1, walksCancelled)
        assertTrue(search.isSearching, "the newer search is still walking: the old one's end isn't its end")
        assertEquals("plain", history[search.hits.list.first().depth].message.take(5))

        walkGate!!.complete(Unit)
        settle()

        assertFalse(search.isSearching)
    }

    @Test
    fun `a term typed again before the debounce has passed starts no walk`() = runWorld(
        needles = setOf(2), loadedCount = 10, debounce = 300.milliseconds,
    ) {
        controller.search("nee")
        controller.search("needle")
        controller.search("needle ")

        settle()
        assertEquals(emptyList<String>(), walks, "nothing walked while the term still changes")

        // Only the last term walks, once the pause is long enough
        delay(500)
        settle()

        assertEquals(listOf("needle "), walks)
        assertFalse(search.isSearching)
    }

    @Test
    fun `closing the search cancels the walk, and its answer doesn't open the search again`() = runWorld(
        needles = setOf(2, 30), loadedCount = 10,
    ) {
        walkGate = CompletableDeferred()

        controller.search("needle")
        settle()
        controller.close()
        settle()

        assertEquals(LogSearch.NotSearching, results.value)
        assertEquals(1, walksCancelled)

        walkGate!!.complete(Unit)
        settle()

        assertEquals(LogSearch.NotSearching, results.value)
    }

    @Test
    fun `a search that is closed while the log loads to a hit scrolls nowhere`() = runWorld(
        needles = setOf(30), loadedCount = 10,
    ) {
        loadGate = CompletableDeferred()

        controller.search("needle")
        settle()
        assertEquals(listOf(hash(30) to 30), loads, "the first hit is being loaded")

        controller.close()
        loadGate!!.complete(Unit)
        settle()

        assertEquals(LogSearch.NotSearching, results.value)
        assertEquals(emptyList<String>(), focused)
    }

    @Test
    fun `a step that is overtaken by a new term scrolls to the old hit no more`() = runWorld(
        needles = setOf(2, 30), loadedCount = 10,
    ) {
        controller.search("needle")
        settle()
        loadGate = CompletableDeferred()
        val step = scope.launch { controller.step(SearchStep.NEXT) }
        settle()
        assertEquals(listOf(hash(30) to 30), loads)

        controller.search("plain")
        settle()
        loadGate!!.complete(Unit)
        step.join()

        assertFalse(hash(30) in focused, "the hit that was being loaded isn't scrolled to")
        assertEquals(hash(0), focused.last(), "the last term's first match is")
    }

    @Test
    fun `a hit that the log can't show is dropped, and the next one is scrolled to`() = runWorld(
        needles = setOf(2, 30, 40), loadedCount = 10,
    ) {
        controller.search("needle")
        settle()
        unreachable = setOf(hash(30))

        controller.step(SearchStep.NEXT)

        assertEquals(listOf(2, 40), search.hits.list.map { it.depth }, "the hit that can't be shown is gone")
        assertEquals(2, search.index, "the counter points at the one after it")
        assertEquals(hash(40), focused.last())
        assertEquals("2", search.totalText)
    }

    @Test
    fun `dropping hits stops after a few, so that a step never loops`() = runWorld(
        needles = setOf(2, 30, 31, 32, 33, 34), loadedCount = 10, total = 60,
    ) {
        controller.search("needle")
        settle()
        unreachable = history.map { it.hash }.toSet() - hash(2)

        controller.step(SearchStep.NEXT)

        assertEquals(3, loads.size, "three attempts, then it stops")
        assertEquals(listOf(hash(2)), focused)
        assertEquals(3, search.hits.size, "the first hit and the two not tried are left")
    }

    @Test
    fun `clicking a hit moves the counter to it, and clicking another commit leaves it`() = runWorld(
        needles = setOf(2, 5, 7), loadedCount = 10,
    ) {
        controller.search("needle")
        settle()

        controller.commitSelected(hash(7))
        assertEquals(3, search.index)

        controller.commitSelected(hash(1))
        assertEquals(3, search.index)
    }

    @Test
    fun `an empty field searches for nothing`() = runWorld(needles = setOf(2), loadedCount = 10) {
        controller.search("needle")
        settle()

        controller.search("   ")
        settle()

        assertEquals(LogSearch.SearchResults.NoTerm, results.value)
        assertEquals(listOf("needle"), walks, "no walk for the empty field")
    }

    @Test
    fun `a walk that fails leaves the loaded commits' answer, with no plus`() = runWorld(
        needles = setOf(2, 30), loadedCount = 10,
    ) {
        walkFails = true

        controller.search("needle")
        settle()

        assertEquals(listOf(2), search.hits.list.map { it.depth })
        assertFalse(search.isSearching)
        assertEquals("1", search.totalText)
    }

    @Test
    fun `hits that stopped at the limit say so in the counter`() = runWorld(
        needles = setOf(2, 5), loadedCount = 10,
    ) {
        walkResult = FoundCommits(listOf(SearchHit(hash(2), 2), SearchHit(hash(5), 5)), reachedHitLimit = true)

        controller.search("needle")
        settle()

        assertTrue(search.isCapped)
        assertEquals("2+", search.totalText)
    }

    @Test
    fun `nothing is left running once the search is closed`(): Unit = runBlocking {
        val world = World(this, needles = setOf(2, 30), loadedCount = 10)
        world.walkGate = CompletableDeferred()

        world.controller.search("needle")
        settle()
        world.controller.search("plain")
        settle()
        world.controller.close()
        settle()

        assertEquals(listOf("needle", "plain"), world.walks)
        assertEquals(2, world.walksCancelled, "both walks were stopped")
        // The test's own scope also waits for its children when the block ends, so one still running would hang it
        assertTrue(currentCoroutineContext()[Job]!!.children.none { it.isActive }, "a coroutine is still running")
    }
}
