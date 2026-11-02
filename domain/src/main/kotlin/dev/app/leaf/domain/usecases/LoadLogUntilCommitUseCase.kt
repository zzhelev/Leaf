// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/** How many more commits a search for one commit may load, at most (fork-only, upstream issue 270). */
const val MAX_COMMITS_LOADED_FOR_ONE = 25_000

/** The first step is one page of the log, as scrolling to its end loads. Each step after it is twice as large. */
private const val FIRST_STEP = 500

/** How often a step may find that nothing was loaded, because the log wasn't ready, before the search gives up. */
private const val MAX_STEPS_WITHOUT_LOADING = 3

/**
 * How many commits past a hit's depth the first step loads, when the search says how deep the commit is. The log's pages
 * start from the lanes that the page before left open, which can order commits a little differently from a walk of the
 * whole history, so the commit may be a few places further down than the walk found it.
 */
private const val EXPECTED_DEPTH_MARGIN = 100

private val LOG_READY_TIMEOUT = 10.seconds

/** How a search for a commit in the log ended. */
sealed interface LoadLogResult {
    /** The log has the commit now. */
    data object Found : LoadLogResult

    /** Every commit that the log can reach is loaded, and the commit isn't one of them. */
    data object NotReachable : LoadLogResult

    /** The search loaded [loaded] more commits, as many as it may, without finding the commit. */
    data class LimitReached(val loaded: Int) : LoadLogResult

    /** The log isn't loaded, or couldn't be loaded, so there was nothing to search. */
    data object LogUnavailable : LoadLogResult
}

/**
 * Loads the log further, as its paging does, until it has the commit with [hash] (fork-only, upstream issue 270). The
 * commit details link to a commit's parents, and a parent may be older than the commits that the log loaded so far.
 *
 * It loads a page, then twice as many commits at each step after it, and gives up once it loaded
 * [MAX_COMMITS_LOADED_FOR_ONE] more, or when there is no more log. The walk behind each step costs about the same
 * whatever it loads (a probe on 66,000 commits: 155 ms for a page, 190 ms for 10,000), so doubling finds a commit that
 * is far away in a few steps and one that is close by without loading much more than a page. A step can't be
 * stopped once it started, so cancelling takes effect when it ends.
 *
 * The log's search knows how deep the commit is, as it walked the whole history (fork-only, upstream issue 277). With
 * [expectedDepth], the first step loads that far in one go, plus a margin, instead of the steps that double; a commit
 * that is still missing after it is looked for by the doubling steps as before.
 */
class LoadLogUntilCommitUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val increaseLogCountUseCase: IncreaseLogCountUseCase,
) {
    /** [onProgress] gets the number of commits loaded, after each step that didn't find the commit. */
    suspend operator fun invoke(
        hash: String,
        maxCommits: Int = MAX_COMMITS_LOADED_FOR_ONE,
        expectedDepth: Int? = null,
        onProgress: (loadedCommits: Int) -> Unit = {},
    ): LoadLogResult {
        val startLog = awaitLoadedLog() ?: return LoadLogResult.LogUnavailable
        val ceiling = startLog.size + maxCommits
        var step = FIRST_STEP
        var stepsWithoutLoading = 0
        var firstStepTo = expectedDepth?.plus(EXPECTED_DEPTH_MARGIN)

        while (true) {
            currentCoroutineContext().ensureActive()

            val before = awaitLoadedLog() ?: return LoadLogResult.LogUnavailable

            // The log may have the commit already, or a refresh that came in meanwhile may have put it in
            if (before.containsKey(hash)) return LoadLogResult.Found

            if (before.size >= ceiling) return LoadLogResult.LimitReached(before.size - startLog.size)

            // The first step goes as deep as the search says, when that is deeper than the log is
            val newLimit = min(firstStepTo?.takeIf { it > before.size } ?: (before.size + step), ceiling)
            firstStepTo = null

            increaseLogCountUseCase(newLimit)

            val after = awaitLoadedLog() ?: return LoadLogResult.LogUnavailable

            if (after.containsKey(hash)) return LoadLogResult.Found

            // IncreaseLogCountUseCase does nothing while the log is loading, or when it failed to load
            if (repositoryDataRepository.maxCommitsToLoadLimit < newLimit) {
                if (++stepsWithoutLoading >= MAX_STEPS_WITHOUT_LOADING) return LoadLogResult.LogUnavailable

                continue
            }

            // A walk takes one commit more than the limit when there is more, and fewer when the log ran out
            if (after.size <= newLimit) return LoadLogResult.NotReachable

            onProgress(after.size - startLog.size)
            step *= 2
        }
    }

    /** The log once it isn't loading, or null when it can't be loaded or doesn't load in time. */
    private suspend fun awaitLoadedLog(): GraphCommits? = withTimeoutOrNull(LOG_READY_TIMEOUT) {
        (repositoryDataRepository.log.first { it !is DataState.Loading } as? DataState.Loaded)?.data
    }
}
