// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.usecases.LoadLogResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(name: String, vararg parents: String) = Commit(
    hash = name.padEnd(40, '0'),
    message = "Subject of $name\n\nBody.",
    committer = someone,
    author = someone,
    date = 1_760_000_000,
    parentsHashes = parents.map { it.padEnd(40, '0') },
)

private fun graphCommit(commit: Commit) = GraphCommit(
    commit = commit,
    lane = 0,
    passingLanes = emptyList(),
    mergingLanes = emptyList(),
    forkingOffLanes = emptyList(),
    childCount = 1,
)

private fun logOf(vararg commits: Commit) = GraphCommits(
    LinkedHashMap<String, GraphCommit>().apply { commits.forEach { put(it.hash, graphCommit(it)) } },
)

/**
 * What the commit details do with a commit's parents (fork-only, upstream issue 270): the rows they list, the jump to
 * a parent that the log has, and the search for one that it hasn't loaded yet. The search itself, which loads the log,
 * is a fake here: `LoadingLogUntilCommitTest` runs the real one against repositories.
 */
class CommitParentsControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val selected = MutableStateFlow<Commit?>(null)
    private val log = MutableStateFlow(GraphCommits())
    private val stashes = MutableStateFlow(emptySet<String>())

    private val selections = CopyOnWriteArrayList<Commit>()
    private val searches = CopyOnWriteArrayList<String>()

    private var search: suspend (String, (Int) -> Unit) -> LoadLogResult = { _, _ -> error("The log wasn't to be searched") }

    // A merge of two parents, the first in the log, and a commit that has neither
    private val older = commit("a1")
    private val merged = commit("b2")
    private val merge = commit("c3", "a1", "b2")
    private val unrelated = commit("d4")

    private val controller = CommitParentsController(
        scope = scope,
        selectedCommit = selected,
        loadedCommits = log,
        stashes = stashes,
        loadLogUntilCommit = { hash, onProgress ->
            searches += hash
            search(hash, onProgress)
        },
        select = { selections += it },
        logStateTimeout = 300.milliseconds,
    )

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private suspend fun awaitState(predicate: (CommitParentsState) -> Boolean): CommitParentsState =
        withTimeout(5.seconds) { controller.state.first { it != null && predicate(it) }!! }

    /**
     * Selects [commit] and waits for its details. They come after what belonged to the commit before has ended, so a
     * click that follows is on the new commit's rows.
     */
    private suspend fun show(commit: Commit) {
        selected.value = commit
        awaitState { it.commit == commit }
    }

    private suspend fun awaitJump(jump: ParentJump) {
        awaitState { it.jump == jump }
    }

    @Test
    fun `no commit selected, nothing to show`(): Unit = runBlocking {
        assertNull(controller.state.value)
        show(merge)
        awaitState { true }
        selected.value = null
        withTimeout(5.seconds) { controller.state.first { it == null } }
    }

    @Test
    fun `lists the selected commit's parents, and which of them the log has`(): Unit = runBlocking {
        log.value = logOf(older, merge)
        show(merge)

        val state = awaitState { it.links.shown.size == 2 }

        assertEquals(merge, state.commit)
        assertEquals(listOf(older.hash, merged.hash), state.links.shown.map { it.hash })
        assertEquals(listOf(true, false), state.links.shown.map { it.isLoaded })
        assertEquals(ParentJump.None, state.jump)
        assertFalse(state.isExpanded)
    }

    @Test
    fun `a parent that reaches the log while its commit is shown becomes a loaded row`(): Unit = runBlocking {
        show(merge)
        awaitState { it.links.shown.none { link -> link.isLoaded } }

        log.value = logOf(older, merged, merge)

        awaitState { it.links.shown.all { link -> link.isLoaded } }
    }

    @Test
    fun `a click on a parent that the log has selects it at once, and asks the log for nothing`(): Unit = runBlocking {
        log.value = logOf(older, merge)
        show(merge)
        awaitState { it.links.shown.first().isLoaded }

        controller.onParentClicked(older.hash)

        assertEquals(listOf(older), selections.toList())
        assertTrue(searches.isEmpty())
        assertEquals(ParentJump.None, controller.state.value!!.jump)
    }

    @Test
    fun `a click on a parent that the log lacks looks for it, shows how far it is, and selects it once it is there`(): Unit = runBlocking {
        log.value = logOf(older, merge)
        show(merge)
        awaitState { it.links.shown.first().isLoaded }
        val step = CompletableDeferred<Unit>()
        val found = CompletableDeferred<Unit>()

        search = { hash, onProgress ->
            onProgress(500)
            step.await()
            log.value = logOf(older, merged, merge)
            found.complete(Unit)
            LoadLogResult.Found
        }

        controller.onParentClicked(merged.hash)

        awaitJump(ParentJump.Looking(merged.hash, loaded = 500))
        assertTrue(selections.isEmpty())

        step.complete(Unit)
        found.await()
        awaitJump(ParentJump.None)

        assertEquals(listOf(merged), selections.toList())
        assertEquals(listOf(merged.hash), searches.toList())
    }

    @Test
    fun `a parent that was found but never reaches the log's state is reported, not selected`(): Unit = runBlocking {
        show(merge)
        search = { _, _ -> LoadLogResult.Found }
        awaitState { true }

        controller.onParentClicked(merged.hash)

        awaitJump(ParentJump.NotFound(merged.hash, LoadLogResult.LogUnavailable))
        assertTrue(selections.isEmpty())
    }

    @Test
    fun `a search that ends without the parent says why, and the row keeps saying it until another commit is selected`(): Unit = runBlocking {
        val answers = listOf(
            LoadLogResult.NotReachable,
            LoadLogResult.LimitReached(25_000),
            LoadLogResult.LogUnavailable,
        )

        for (answer in answers) {
            show(merge)
            awaitState { it.commit == merge }
            search = { _, _ -> answer }

            controller.onParentClicked(merged.hash)

            awaitJump(ParentJump.NotFound(merged.hash, answer))
            delay(50)
            assertEquals(ParentJump.NotFound(merged.hash, answer), controller.state.value!!.jump, "stays")
            assertTrue(selections.isEmpty())

            show(unrelated)
            awaitJump(ParentJump.None)
        }
    }

    @Test
    fun `a click on a row that is being looked for stops the search, and another click starts it again`(): Unit = runBlocking {
        show(merge)
        val cancelled = AtomicBoolean()
        search = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }

        controller.onParentClicked(merged.hash)
        awaitJump(ParentJump.Looking(merged.hash))

        controller.onParentClicked(merged.hash)

        awaitJump(ParentJump.None)
        withTimeout(5.seconds) { while (!cancelled.get()) delay(5) }
        assertTrue(selections.isEmpty())

        cancelled.set(false)
        controller.onParentClicked(merged.hash)

        awaitJump(ParentJump.Looking(merged.hash))
        assertEquals(listOf(merged.hash, merged.hash), searches.toList())
    }

    @Test
    fun `selecting another commit stops the search for a parent`(): Unit = runBlocking {
        show(merge)
        val cancelled = AtomicBoolean()
        search = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        controller.onParentClicked(merged.hash)
        awaitJump(ParentJump.Looking(merged.hash))

        show(unrelated)

        awaitJump(ParentJump.None)
        withTimeout(5.seconds) { while (!cancelled.get()) delay(5) }
        assertTrue(selections.isEmpty())
    }

    @Test
    fun `a step that ends after the user moved on doesn't bring the row back`(): Unit = runBlocking {
        show(merge)
        search = { _, onProgress ->
            try {
                awaitCancellation()
            } finally {
                // The step that was running when the search was stopped ends, and reports as it always does
                onProgress(900)
            }
        }
        controller.onParentClicked(merged.hash)
        awaitJump(ParentJump.Looking(merged.hash))

        show(unrelated)
        awaitJump(ParentJump.None)
        delay(150)

        assertEquals(ParentJump.None, controller.state.value!!.jump)
    }

    @Test
    fun `a click on another parent while one is looked for switches to the other`(): Unit = runBlocking {
        val third = commit("e5")
        val octopus = commit("f6", "a1", "b2", "e5")
        show(octopus)
        val cancelled = CopyOnWriteArrayList<String>()
        search = { hash, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled += hash
            }
        }

        controller.onParentClicked(merged.hash)
        awaitJump(ParentJump.Looking(merged.hash))

        controller.onParentClicked(third.hash)

        awaitJump(ParentJump.Looking(third.hash))
        withTimeout(5.seconds) { while (cancelled.isEmpty()) delay(5) }
        assertEquals(listOf(merged.hash), cancelled.toList())
    }

    @Test
    fun `a stash lists only the commit it was made on`(): Unit = runBlocking {
        val index = commit("a9")
        val stash = commit("c3", "a1", "a9")
        stashes.value = setOf(stash.hash)
        log.value = logOf(older, stash)
        show(stash)

        val state = awaitState { it.commit == stash }

        assertEquals(listOf(older.hash), state.links.shown.map { it.hash })
        assertFalse(state.links.isNumbered)
        assertFalse(index.hash in state.links.all.map { it.hash })
    }

    @Test
    fun `parents beyond the first few open and close, and close when another commit is selected`(): Unit = runBlocking {
        val many = commit("f6", *Array(6) { "a$it" })
        show(many)
        assertEquals(3, awaitState { it.commit == many }.links.shown.size)

        controller.toggleExpanded()
        awaitState { it.isExpanded }
        controller.toggleExpanded()
        awaitState { !it.isExpanded }
        controller.toggleExpanded()
        awaitState { it.isExpanded }

        show(merge)

        awaitState { it.commit == merge && !it.isExpanded }
    }

    @Test
    fun `a click on a hidden parent works as on any other`(): Unit = runBlocking {
        val sixth = commit("a5")
        val many = commit("f6", *Array(6) { "a$it" })
        log.value = logOf(sixth, many)
        show(many)
        awaitState { it.commit == many }
        controller.toggleExpanded()
        val state = awaitState { it.isExpanded && it.links.hidden.any { link -> link.isLoaded } }

        controller.onParentClicked(state.links.hidden.first { it.isLoaded }.hash)

        assertEquals(listOf(sixth), selections.toList())
    }
}
