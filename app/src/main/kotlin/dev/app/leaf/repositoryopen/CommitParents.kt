// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.log.ParentLinks
import dev.app.leaf.domain.log.parentLinks
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.usecases.LoadLogResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the commit details say about the selected commit's parents (fork-only, upstream issue 270): [links] are the
 * rows, [isExpanded] tells whether the ones behind "Show N more parents" show, and [jump] what a click on a parent is
 * doing.
 */
data class CommitParentsState(
    val commit: Commit,
    val links: ParentLinks,
    val isExpanded: Boolean = false,
    val jump: ParentJump = ParentJump.None,
)

/** What a click on a parent row is doing. It ends, whatever it did, when another commit is selected. */
sealed interface ParentJump {
    data object None : ParentJump

    /** Loading the log further until it has the parent, with [loaded] more commits so far. */
    data class Looking(val hash: String, val loaded: Int = 0) : ParentJump

    /** The parent isn't in the log: [result] says why, as one of the answers that aren't [LoadLogResult.Found]. */
    data class NotFound(val hash: String, val result: LoadLogResult) : ParentJump
}

/**
 * Lists the selected commit's parents and selects one when it is clicked (fork-only, upstream issue 270).
 *
 * A parent that the log has is selected at once, which scrolls the log to it. One that is older than the commits
 * loaded so far starts [loadLogUntilCommit], which loads the log further in the background, and is selected when it
 * arrives. A second click on that row, or selecting any other commit, stops the search. Where it ended without a
 * parent, the row says why until another commit is selected: a toast would be gone before it was read.
 */
class CommitParentsController(
    private val scope: CoroutineScope,
    selectedCommit: Flow<Commit?>,
    private val loadedCommits: StateFlow<GraphCommits>,
    stashes: Flow<Set<String>>,
    private val loadLogUntilCommit: suspend (hash: String, onProgress: (loaded: Int) -> Unit) -> LoadLogResult,
    private val select: (Commit) -> Unit,
    private val logStateTimeout: Duration = LOG_STATE_TIMEOUT,
) {
    private val jump = MutableStateFlow<ParentJump>(ParentJump.None)
    private val isExpanded = MutableStateFlow(false)

    @Volatile
    private var searchJob: Job? = null

    /** The hash that [state] was last made for. Only the collector of [state] reads and writes it. */
    private var shownHash: String? = null

    val state: StateFlow<CommitParentsState?> = combine(
        // What belongs to a commit's details ends when another commit is selected, which a jump does too. It ends
        // here, before the state for the new commit is made, so that a click on that state's rows can't come before it
        selectedCommit.onEach { commit ->
            if (commit?.hash != shownHash) {
                shownHash = commit?.hash
                reset()
            }
        },
        loadedCommits,
        stashes,
        jump,
        isExpanded,
    ) { commit, log, stashHashes, jump, isExpanded ->
        commit?.let {
            CommitParentsState(
                commit = it,
                links = parentLinks(it, isStash = it.hash in stashHashes) { hash -> log[hash]?.commit },
                isExpanded = isExpanded,
                jump = jump,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** A click on the parent with [hash]: select it, or stop the search for it that this click started before. */
    fun onParentClicked(hash: String) {
        val current = jump.value

        if (current is ParentJump.Looking && current.hash == hash) {
            stopSearch()
            return
        }

        searchJob?.cancel()

        val loaded = loadedCommits.value[hash]?.commit

        if (loaded != null) {
            jump.value = ParentJump.None
            select(loaded)
            return
        }

        jump.value = ParentJump.Looking(hash)
        searchJob = scope.launch { search(hash) }
    }

    fun toggleExpanded() {
        isExpanded.update { !it }
    }

    private suspend fun search(hash: String) {
        val result = loadLogUntilCommit(hash) { loaded ->
            // Only while this search is the jump: a step that ends after the user moved on must not bring it back
            jump.update { if (it is ParentJump.Looking && it.hash == hash) it.copy(loaded = loaded) else it }
        }

        currentCoroutineContext().ensureActive()

        if (result != LoadLogResult.Found) {
            finishLooking(hash, ParentJump.NotFound(hash, result))
            return
        }

        // The log's state follows the loaded data a moment later, and the log scrolls to what it has when it is asked
        val commit = withTimeoutOrNull(logStateTimeout) {
            loadedCommits.first { it.containsKey(hash) }[hash]?.commit
        }

        currentCoroutineContext().ensureActive()

        if (commit == null) {
            finishLooking(hash, ParentJump.NotFound(hash, LoadLogResult.LogUnavailable))
            return
        }

        finishLooking(hash, ParentJump.None)
        select(commit)
    }

    private fun finishLooking(hash: String, outcome: ParentJump) {
        jump.update { if (it is ParentJump.Looking && it.hash == hash) outcome else it }
    }

    private fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        jump.value = ParentJump.None
    }

    private fun reset() {
        stopSearch()
        isExpanded.value = false
    }
}

private val LOG_STATE_TIMEOUT = 5.seconds
