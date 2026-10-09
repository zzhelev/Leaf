// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

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
}
