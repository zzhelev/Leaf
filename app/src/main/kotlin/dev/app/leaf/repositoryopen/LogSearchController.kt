// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.log.SearchStep
import dev.app.leaf.domain.log.matchesLogSearch
import dev.app.leaf.domain.log.steppedSearchIndex
import dev.app.leaf.domain.models.FoundCommits
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.models.SearchHit
import dev.app.leaf.domain.usecases.LoadLogResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long a term has to stay as typed before the walk of the whole history starts. A walk that a newer term cancels
 * still reads the history before it notices, which takes a second or so on a repository of 200,000 commits, so a
 * quick run of letters starts none but the last.
 */
val SEARCH_DEBOUNCE: Duration = 120.milliseconds

/** How many hits in a row that the log can't show a step may drop before it stops, so that it never loops for long. */
private const val MAX_DROPPED_HITS_PER_STEP = 3

/**
 * The log's search, over the whole history and not only the commits that the log has loaded (fork-only, upstream issue
 * 277). It writes the search's state to [results], which the log's rows and counter read.
 *
 * A term is answered twice. The loaded commits are filtered at once, so the first matches show as they always did, with
 * the counter's "+" for more to come. Then [searchHistory] walks the whole history, and its hits replace them, in the
 * order of the log, the counter keeping the commit that it pointed at. A term with no match among the loaded commits
 * opens the first hit that the walk finds, loading the log to it.
 *
 * Stepping through the hits wraps at the ends. A hit that the log hasn't loaded is shown by loading the log to its depth
 * first ([loadLogUntilCommit]), one that it can't show is dropped from the list. Each new term cancels the search of
 * the one before, and so does closing the search: [scope] is the view model's, so nothing outlives the tab.
 */
class LogSearchController(
    private val scope: CoroutineScope,
    private val results: MutableStateFlow<LogSearch>,
    private val loadedCommits: () -> GraphCommits,
    /** The hits in the whole history for a lower-case term, or null when it couldn't be searched. */
    private val searchHistory: suspend (lowercaseTerm: String) -> FoundCommits?,
    /** Loads the log until it has the commit, which [searchHistory] found [expectedDepth] commits down. */
    private val loadLogUntilCommit: suspend (hash: String, expectedDepth: Int) -> LoadLogResult,
    /** Scrolls the log to a commit that it has loaded. */
    private val focus: suspend (GraphCommit) -> Unit,
    private val debounce: Duration = SEARCH_DEBOUNCE,
) {
    /** The search for the last term typed. Each new term cancels it, so that a slower earlier search can't win. */
    @Volatile
    private var job: Job? = null

    fun search(term: String): Job {
        job?.cancel()

        return scope.launch { run(term) }.also { job = it }
    }

    private suspend fun run(term: String) {
        val context = currentCoroutineContext()

        if (term.isBlank()) {
            results.update { if (context.isActive) LogSearch.SearchResults.NoTerm else it }

            return
        }

        val lowercaseTerm = term.lowercase()
        val loadedHits = loadedHits(lowercaseTerm)

        // A newer term, or closing the search, may have come while the commits were filtered
        context.ensureActive()

        val first = loadedHits.firstOrNull()

        results.update {
            if (context.isActive) {
                LogSearch.SearchResults(SearchHits(loadedHits), index = if (first == null) 0 else 1, isSearching = true)
            } else {
                it
            }
        }

        first?.let { showHit(it) }

        delay(debounce)

        val found = searchHistory(lowercaseTerm)

        // JGit turns a cancellation into an error result, which isn't an answer
        context.ensureActive()

        finish(found)
    }

    private suspend fun loadedHits(lowercaseTerm: String): List<SearchHit> {
        val context = currentCoroutineContext()
        val hits = ArrayList<SearchHit>()

        loadedCommits().values.forEachIndexed { depth, commit ->
            if (depth % CANCELLATION_CHECK_EVERY == 0) context.ensureActive()

            if (commit.commit.matchesLogSearch(lowercaseTerm)) hits += SearchHit(commit.hash, depth)
        }

        return hits
    }

    /** Puts the hits of the walk in place of the loaded commits' matches, and opens the first one if none was shown. */
    private suspend fun finish(found: FoundCommits?) {
        var toOpen: SearchHit? = null

        results.update { current ->
            // The block runs again when another write came in between
            toOpen = null

            if (current !is LogSearch.SearchResults) return@update current

            if (found == null) return@update current.copy(isSearching = false)

            val hits = SearchHits(found.hits)
            val focused = current.focusedHit?.hash
            val focusedNumber = focused?.let { hits.numberOf(it) } ?: 0

            // The commit that the counter pointed at stays the one it points at. Without one, the first hit opens
            val index = when {
                hits.size == 0 -> 0
                focusedNumber > 0 -> focusedNumber
                else -> 1.also { toOpen = hits.list.first() }
            }

            current.copy(hits = hits, index = index, isSearching = false, isCapped = found.reachedHitLimit)
        }

        toOpen?.let { showHit(it) }
    }

    suspend fun step(step: SearchStep) {
        var hit: SearchHit? = null

        // `update` keeps a step from putting an older list back over the hits of a search finished meanwhile
        results.update { current ->
            // The block runs again when another write came in between
            hit = null

            if (current !is LogSearch.SearchResults) return@update current

            val newIndex = steppedSearchIndex(current.index, current.totalCount, step)

            hit = current.hits.list.getOrNull(newIndex - 1) // The index starts at 1, the list at 0

            if (newIndex == current.index) current else current.copy(index = newIndex)
        }

        hit?.let { showHit(it) }
    }

    /** A commit that the user selected moves the counter to it, when it is a hit. */
    fun commitSelected(hash: String) {
        results.update { current ->
            if (current !is LogSearch.SearchResults) return@update current

            val number = current.hits.numberOf(hash)

            if (number == 0) current else current.copy(index = number)
        }
    }

    /** Ends the search, and with it the walk that may still be running. */
    fun close() {
        job?.cancel()
        results.value = LogSearch.NotSearching
    }

    /**
     * Scrolls the log to [first], loading the log to it when it isn't loaded. A hit that the log can't show is dropped
     * and the next one is tried, a few times. Nothing is scrolled when the counter has moved on in the meantime, as the
     * loading takes a while and the user may have typed or stepped.
     */
    private suspend fun showHit(first: SearchHit) {
        var hit = first

        repeat(MAX_DROPPED_HITS_PER_STEP) {
            val loaded = loadedCommits()[hit.hash]
            val commit = loaded ?: when (loadLogUntilCommit(hit.hash, hit.depth)) {
                LoadLogResult.Found -> loadedCommits()[hit.hash]
                else -> null
            }

            if ((results.value as? LogSearch.SearchResults)?.focusedHit?.hash != hit.hash) return

            if (commit != null) {
                focus(commit)

                return
            }

            hit = dropped(hit.hash) ?: return
        }
    }

    /** Takes the hit out of the list, and returns the one that the counter points at now, if any. */
    private fun dropped(hash: String): SearchHit? {
        var next: SearchHit? = null

        results.update { current ->
            next = null

            if (current !is LogSearch.SearchResults) return@update current

            val hits = current.hits.without(hash)
            // The number stays, so the counter points at the hit after the dropped one, or wraps to the first
            val index = if (hits.size == 0) 0 else current.index.takeIf { it <= hits.size } ?: 1

            next = hits.list.getOrNull(index - 1)

            current.copy(hits = hits, index = index)
        }

        return next
    }
}

private const val CANCELLATION_CHECK_EVERY = 1024
