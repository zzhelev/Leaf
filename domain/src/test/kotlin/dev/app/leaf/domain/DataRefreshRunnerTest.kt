// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain

import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class DataRefreshRunnerTest {
    @Test
    fun `refreshes one at a time`(): Unit = runWithTimeout {
        val runner = DataRefreshRunner()
        val running = AtomicInteger()
        var overlapped = false

        (1..20).map {
            launch(Dispatchers.Default) {
                runner.run(setOf(DataToRefresh.LOG)) {
                    if (running.incrementAndGet() > 1) overlapped = true
                    delay(5)
                    running.decrementAndGet()
                }
            }
        }.forEach { it.join() }

        assertFalse(overlapped)
    }

    @Test
    fun `calls made while one waits join its refresh, with what they ask for`(): Unit = runWithTimeout {
        val runner = DataRefreshRunner()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val refreshes = Collections.synchronizedList(mutableListOf<Set<DataToRefresh>>())

        val first = launch(Dispatchers.Default) {
            runner.run(setOf(DataToRefresh.LOG)) {
                refreshes.add(it)
                firstStarted.complete(Unit)
                finishFirst.await()
            }
        }
        firstStarted.await()

        val second = launch(Dispatchers.Default) { runner.run(setOf(DataToRefresh.STATUS)) { refreshes.add(it) } }
        waitUntil { runner.hasCallWaiting() }

        val joined = listOf(DataToRefresh.BRANCHES, DataToRefresh.STATUS, DataToRefresh.TAGS).map { data ->
            async(Dispatchers.Default) {
                runner.run(setOf(data)) { refreshes.add(setOf(DataToRefresh.ALL)) }
                "returned"
            }
        }

        // They return before the refresh that does their part
        assertEquals(listOf("returned", "returned", "returned"), joined.map { it.await() })
        assertEquals(listOf(setOf(DataToRefresh.LOG)), refreshes.toList())

        finishFirst.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            listOf(
                setOf(DataToRefresh.LOG),
                setOf(DataToRefresh.STATUS, DataToRefresh.BRANCHES, DataToRefresh.TAGS),
            ),
            refreshes.toList(),
        )
    }

    @Test
    fun `a call cancelled while it waits lets the next one wait instead`(): Unit = runWithTimeout {
        val runner = DataRefreshRunner()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val refreshes = Collections.synchronizedList(mutableListOf<Set<DataToRefresh>>())

        val first = launch(Dispatchers.Default) {
            runner.run(setOf(DataToRefresh.LOG)) {
                firstStarted.complete(Unit)
                finishFirst.await()
                refreshes.add(it)
            }
        }
        firstStarted.await()

        val cancelled = launch(Dispatchers.Default) { runner.run(setOf(DataToRefresh.STASHES)) { refreshes.add(it) } }
        waitUntil { runner.hasCallWaiting() }
        cancelled.cancelAndJoin()

        val next = launch(Dispatchers.Default) { runner.run(setOf(DataToRefresh.STATUS)) { refreshes.add(it) } }
        waitUntil { runner.hasCallWaiting() }
        finishFirst.complete(Unit)
        first.join()
        next.join()

        assertEquals(listOf(setOf(DataToRefresh.LOG), setOf(DataToRefresh.STATUS)), refreshes.toList())
    }

    @Test
    fun `a refresh that fails doesn't block the next one`(): Unit = runWithTimeout {
        val runner = DataRefreshRunner()

        val failure = runCatching { runner.run(setOf(DataToRefresh.LOG)) { error("failed") } }
        assertEquals("failed", failure.exceptionOrNull()?.message)

        var refreshed: Set<DataToRefresh>? = null
        runner.run(setOf(DataToRefresh.STATUS)) { refreshed = it }

        assertEquals(setOf(DataToRefresh.STATUS), refreshed)
    }

    @Test
    fun `work that runs alone waits for the refresh, and the next refresh waits for it`(): Unit = runWithTimeout {
        val runner = DataRefreshRunner()
        val refreshStarted = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        val steps = Collections.synchronizedList(mutableListOf<String>())

        val refresh = launch(Dispatchers.Default) {
            runner.run(setOf(DataToRefresh.LOG)) {
                refreshStarted.complete(Unit)
                finishRefresh.await()
                steps.add("refresh")
            }
        }
        refreshStarted.await()

        val alone = async(Dispatchers.Default) {
            runner.runAlone {
                steps.add("alone")
                "result"
            }
        }
        // Not before the refresh ends
        delay(50)
        assertEquals(emptyList<String>(), steps.toList())

        finishRefresh.complete(Unit)
        assertEquals("result", alone.await())
        refresh.join()

        runner.run(setOf(DataToRefresh.STATUS)) { steps.add("next refresh") }

        assertEquals(listOf("refresh", "alone", "next refresh"), steps.toList())
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) yield()
    }

    /** Fails a test whose calls never get through, rather than hanging the build. */
    private fun runWithTimeout(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }
}
