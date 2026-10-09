// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConflatedRunnerTest {
    @Test
    fun `runs once more after the current run, however many calls came meanwhile`(): Unit = runWithTimeout {
        val runner = ConflatedRunner()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val runs = mutableListOf<String>()

        val first = launch(Dispatchers.Default) {
            runner.run {
                runs.add("first")
                firstStarted.complete(Unit)
                finishFirst.await()
            }
        }
        firstStarted.await()

        val second = launch(Dispatchers.Default) { runner.run { runs.add("second") } }
        // The second call waits on the first run before the others come
        waitUntil { runner.hasCallWaiting() }
        val skipped = (1..3).map { index ->
            async(Dispatchers.Default) {
                runner.run { runs.add("skipped $index") }
                "returned"
            }
        }

        assertEquals(listOf("returned", "returned", "returned"), skipped.map { it.await() })
        assertEquals(listOf("first"), runs)

        finishFirst.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("first", "second"), runs)
    }

    @Test
    fun `runs right away when nothing runs`(): Unit = runWithTimeout {
        val runner = ConflatedRunner()
        val runs = mutableListOf<Int>()

        repeat(3) { index -> runner.run { runs.add(index) } }

        assertEquals(listOf(0, 1, 2), runs)
    }

    @Test
    fun `a call cancelled while it waits lets the next one wait instead`(): Unit = runWithTimeout {
        val runner = ConflatedRunner()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val runs = mutableListOf<String>()

        val first = launch(Dispatchers.Default) {
            runner.run {
                firstStarted.complete(Unit)
                finishFirst.await()
                runs.add("first")
            }
        }
        firstStarted.await()

        val cancelled = launch(Dispatchers.Default) { runner.run { runs.add("cancelled") } }
        waitUntil { runner.hasCallWaiting() }
        cancelled.cancelAndJoin()

        val next = launch(Dispatchers.Default) { runner.run { runs.add("next") } }
        waitUntil { runner.hasCallWaiting() }
        finishFirst.complete(Unit)
        first.join()
        next.join()

        assertEquals(listOf("first", "next"), runs)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) yield()
    }

    /** Fails a test whose calls never get through, rather than hanging the build. */
    private fun runWithTimeout(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }
}
