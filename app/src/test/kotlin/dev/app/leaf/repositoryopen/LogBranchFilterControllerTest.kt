// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** What the log's header and menus show while a filter is saved and walked (fork-only, upstream issue 207). */
class LogBranchFilterControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val only = LogBranchFilter.Only(setOf("refs/heads/main"))
    private val hidden = LogBranchFilter.All(setOf("refs/heads/wip/spike"))

    @AfterEach
    fun cancel() = scope.cancel()

    /** A stand-in for saving and walking: each call waits for its gate, then the log has the filter it asked for. */
    private inner class Walks {
        val logFilter = MutableStateFlow<LogBranchFilter?>(null)
        val applied = CopyOnWriteArrayList<LogBranchFilter>()
        val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

        val controller = LogBranchFilterController(scope, logFilter) { filter ->
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
            applied += filter
            logFilter.value = filter
        }

        suspend fun shows(filter: LogBranchFilter) = withTimeout(5.seconds) {
            controller.filter.first { it == filter }
        }

        suspend fun gate(index: Int) = withTimeout(5.seconds) {
            while (gates.size <= index) kotlinx.coroutines.delay(5)
            gates[index]
        }
    }

    @Test
    fun `before any log, every branch shows`(): Unit = runBlocking {
        assertEquals(NO_LOG_BRANCH_FILTER, Walks().controller.filter.value)
    }

    @Test
    fun `a change shows at once, before it is saved and walked`(): Unit = runBlocking {
        val walks = Walks()

        walks.controller.set(only)

        walks.shows(only)
        assertEquals(emptyList<LogBranchFilter>(), walks.applied)

        walks.gate(0).complete(Unit)
        withTimeout(5.seconds) { walks.logFilter.first { it == only } }
        assertEquals(listOf<LogBranchFilter>(only), walks.applied)
    }

    @Test
    fun `changes made while one is walked are joined, and the last one wins`(): Unit = runBlocking {
        val walks = Walks()

        walks.controller.set(only)
        val first = walks.gate(0)

        walks.controller.set(hidden)
        walks.controller.set(LogBranchFilter.Current)
        walks.shows(LogBranchFilter.Current)

        first.complete(Unit)
        // The first walk ended, but a later change waits: it keeps showing
        withTimeout(5.seconds) { walks.logFilter.first { it == only } }
        assertEquals(LogBranchFilter.Current, walks.controller.filter.value)

        walks.gate(1).complete(Unit)
        withTimeout(5.seconds) { walks.logFilter.first { it == LogBranchFilter.Current } }

        assertEquals(listOf(only, LogBranchFilter.Current), walks.applied.toList())
        walks.shows(LogBranchFilter.Current)
    }

    @Test
    fun `with no change waiting, the log's own filter shows, as another tab saved it`(): Unit = runBlocking {
        val walks = Walks()

        walks.logFilter.value = hidden
        walks.shows(hidden)

        walks.logFilter.value = only
        walks.shows(only)
    }

    @Test
    fun `a change that couldn't be applied gives way to the log's filter`(): Unit = runBlocking {
        val logFilter = MutableStateFlow<LogBranchFilter?>(hidden)
        val failing = CompletableDeferred<Unit>()
        // The save failed: the log stays as it was
        val controller = LogBranchFilterController(scope, logFilter) { failing.await() }

        withTimeout(5.seconds) { controller.filter.first { it == hidden } }
        controller.set(only)
        withTimeout(5.seconds) { controller.filter.first { it == only } }

        failing.complete(Unit)

        withTimeout(5.seconds) { controller.filter.first { it == hidden } }
    }
}
