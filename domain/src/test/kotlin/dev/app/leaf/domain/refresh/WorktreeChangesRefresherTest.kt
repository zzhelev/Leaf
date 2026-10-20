// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class WorktreeChangesRefresherTest {
    @Test
    fun `refreshes right away, then once more after the gap for the changes that came meanwhile`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val refreshes = CopyOnWriteArrayList<Long>()
        val refresher = WorktreeChangesRefresher(scope, minGap = 300.milliseconds) {
            refreshes.add(System.nanoTime())
        }

        try {
            val start = System.nanoTime()

            repeat(10) {
                refresher.onChanged()
                delay(10)
            }

            withTimeout(5_000) {
                while (refreshes.size < 2) delay(10)
            }
            // Nothing else is waiting
            delay(500)

            assertEquals(2, refreshes.size)
            assertTrue((refreshes[0] - start) / 1_000_000 < 200, "The first change refreshes right away")
            assertTrue((refreshes[1] - refreshes[0]) / 1_000_000 >= 300, "The next refresh waits for the gap")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a change after the gap refreshes right away`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val refreshes = CopyOnWriteArrayList<Long>()
        val refresher = WorktreeChangesRefresher(scope, minGap = 100.milliseconds) {
            refreshes.add(System.nanoTime())
        }

        try {
            refresher.onChanged()
            delay(300)
            val second = System.nanoTime()
            refresher.onChanged()

            withTimeout(5_000) {
                while (refreshes.size < 2) delay(5)
            }

            assertTrue((refreshes[1] - second) / 1_000_000 < 100)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `index churn never asks for the log`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val refreshes = CopyOnWriteArrayList<Boolean>()
        val refresher = WorktreeChangesRefresher(scope, minGap = 50.milliseconds) { includesLog ->
            refreshes.add(includesLog)
        }

        try {
            // An agent's git rewriting its index for a second
            repeat(100) {
                refresher.onChanged()
                delay(10)
            }
            delay(200)

            assertTrue(refreshes.size >= 2, "It refreshed ${refreshes.size} times")
            assertEquals(emptyList<Boolean>(), refreshes.filter { it })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a HEAD that moved refreshes the log right away, with the same refresh as the list`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val refreshes = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val refresher = WorktreeChangesRefresher(scope, minGap = 300.milliseconds) { includesLog ->
            refreshes.add(System.nanoTime() to includesLog)
        }

        try {
            val start = System.nanoTime()

            refresher.onChanged(includesLog = true)

            withTimeout(5_000) {
                while (refreshes.isEmpty()) delay(5)
            }
            delay(500)

            // One refresh, which was told to refresh the log, and no second one for the list
            assertEquals(listOf(true), refreshes.map { it.second })
            assertTrue((refreshes[0].first - start) / 1_000_000 < 200, "It waited for the gap")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a HEAD that moved while a refresh waits for the gap is in the next refresh, among index changes`(): Unit =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val refreshes = CopyOnWriteArrayList<Pair<Long, Boolean>>()
            val refresher = WorktreeChangesRefresher(scope, minGap = 300.milliseconds) { includesLog ->
                refreshes.add(System.nanoTime() to includesLog)
            }

            try {
                refresher.onChanged()
                withTimeout(5_000) {
                    while (refreshes.isEmpty()) delay(5)
                }

                // In the gap: index changes, then the HEAD, then more index changes
                refresher.onChanged()
                refresher.onChanged(includesLog = true)
                refresher.onChanged()
                refresher.onChanged()

                withTimeout(5_000) {
                    while (refreshes.size < 2) delay(10)
                }
                delay(500)

                assertEquals(listOf(false, true), refreshes.map { it.second })
                assertTrue((refreshes[1].first - refreshes[0].first) / 1_000_000 >= 300, "The next refresh waits for the gap")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a HEAD that moved while a refresh runs asks for the log in the one after it, and the flag doesn't stay`(): Unit =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val refreshes = CopyOnWriteArrayList<Boolean>()
            val running = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val refresher = WorktreeChangesRefresher(scope, minGap = 20.milliseconds) { includesLog ->
                refreshes.add(includesLog)

                if (refreshes.size == 1) {
                    running.complete(Unit)
                    release.await()
                }
            }

            try {
                refresher.onChanged()
                withTimeout(5_000) { running.await() }

                refresher.onChanged(includesLog = true)
                release.complete(Unit)

                withTimeout(5_000) {
                    while (refreshes.size < 2) delay(10)
                }

                // After it, the flag is gone
                refresher.onChanged()
                withTimeout(5_000) {
                    while (refreshes.size < 3) delay(10)
                }

                assertEquals(listOf(false, true, false), refreshes.toList())
            } finally {
                scope.cancel()
            }
        }
}
