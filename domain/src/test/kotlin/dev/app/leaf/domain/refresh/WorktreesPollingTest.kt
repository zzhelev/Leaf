// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.domain.models.WorktreesRefreshIntervals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val SHOWN_FOCUSED = WorktreesVisibility(isShown = true, isWindowFocused = true)
private val SHOWN_UNFOCUSED = WorktreesVisibility(isShown = true, isWindowFocused = false)

class WorktreesPollingTest {
    /** A tab whose collector refreshes the list on each tick, unless [refreshes] says no, as during an operation. */
    private inner class Poller(
        visibility: WorktreesVisibility,
        interval: Duration?,
        lastRefreshAgo: Duration?,
    ) {
        val visibility = MutableStateFlow(visibility)
        val interval = MutableStateFlow(interval)

        @Volatile
        var lastRefreshAt = lastRefreshAgo?.let { System.currentTimeMillis() - it.inWholeMilliseconds } ?: 0L

        @Volatile
        var refreshes = true
        val ticks = CopyOnWriteArrayList<Long>()

        fun CoroutineScope.start() = launch(Dispatchers.Default) {
            worktreePollTicks(this@Poller.visibility, this@Poller.interval, lastRefreshAt = { lastRefreshAt })
                .collect {
                    ticks.add(System.currentTimeMillis())

                    if (refreshes) {
                        lastRefreshAt = System.currentTimeMillis()
                    }
                }
        }
    }

    @Test
    fun `refreshes right away when the list is older than the interval, then on the interval`(): Unit = runBlocking {
        val poller = Poller(SHOWN_UNFOCUSED, interval = 200.milliseconds, lastRefreshAgo = null)
        val start = System.currentTimeMillis()
        val job = with(poller) { start() }

        awaitTicks(poller, 3)
        job.cancelAndJoin()

        val ticks = poller.ticks
        assertTrue(ticks[0] - start < 150, "first tick after ${ticks[0] - start} ms")
        assertTrue(ticks[1] - ticks[0] >= 195, "second tick after ${ticks[1] - ticks[0]} ms")
        assertTrue(ticks[2] - ticks[1] >= 195, "third tick after ${ticks[2] - ticks[1]} ms")
    }

    @Test
    fun `waits for what's left of the interval since the last refresh`(): Unit = runBlocking {
        val poller = Poller(SHOWN_UNFOCUSED, interval = 10.seconds, lastRefreshAgo = 9_700.milliseconds)
        val start = System.currentTimeMillis()
        val job = with(poller) { start() }

        delay(100)
        assertEquals(0, poller.ticks.size)

        awaitTicks(poller, 1)
        job.cancelAndJoin()

        val waited = poller.ticks[0] - start
        assertTrue(waited in 250..1_000, "first tick after $waited ms")
    }

    @Test
    fun `a refresh from elsewhere moves the next tick`(): Unit = runBlocking {
        val poller = Poller(SHOWN_UNFOCUSED, interval = 400.milliseconds, lastRefreshAgo = 300.milliseconds)
        val job = with(poller) { start() }

        delay(50)
        // The branches, the log or the file watcher refreshed the list
        poller.lastRefreshAt = System.currentTimeMillis()
        val refreshedAt = poller.lastRefreshAt

        awaitTicks(poller, 1)
        job.cancelAndJoin()

        val waited = poller.ticks[0] - refreshedAt
        assertTrue(waited >= 395, "tick $waited ms after the other refresh")
    }

    @Test
    fun `nothing while the list is hidden or the refresh is off`(): Unit = runBlocking {
        val hidden = Poller(WorktreesVisibility.Hidden, interval = 50.milliseconds, lastRefreshAgo = null)
        val off = Poller(SHOWN_FOCUSED, interval = null, lastRefreshAgo = null)
        val jobs = listOf(with(hidden) { start() }, with(off) { start() })

        delay(300)
        assertEquals(0, hidden.ticks.size)
        assertEquals(0, off.ticks.size)

        // Shown, or turned on
        hidden.visibility.value = SHOWN_UNFOCUSED
        off.interval.value = 50.milliseconds
        awaitTicks(hidden, 1)
        awaitTicks(off, 1)

        // Hidden again, as when the tab or the section closes
        hidden.visibility.value = WorktreesVisibility.Hidden
        delay(100)
        val count = hidden.ticks.size
        delay(300)
        assertEquals(count, hidden.ticks.size)

        jobs.forEach { it.cancelAndJoin() }
    }

    @Test
    fun `the window getting the focus refreshes right away, unless the list was refreshed in the last second`(): Unit =
        runBlocking {
            val poller = Poller(SHOWN_UNFOCUSED, interval = 10.seconds, lastRefreshAgo = 2.seconds)
            val job = with(poller) { start() }

            delay(100)
            assertEquals(0, poller.ticks.size)

            val focusedAt = System.currentTimeMillis()
            poller.visibility.value = SHOWN_FOCUSED
            awaitTicks(poller, 1)
            assertTrue(poller.ticks[0] - focusedAt < 150)

            // The list was just refreshed
            poller.visibility.value = SHOWN_UNFOCUSED
            delay(50)
            poller.visibility.value = SHOWN_FOCUSED
            delay(300)
            assertEquals(1, poller.ticks.size)

            job.cancelAndJoin()
        }

    @Test
    fun `showing the list with the window focused isn't a focus change`(): Unit = runBlocking {
        val poller = Poller(WorktreesVisibility.Hidden, interval = 10.seconds, lastRefreshAgo = 2.seconds)
        val job = with(poller) { start() }

        delay(50)
        // Switching back to the tab, or expanding the section
        poller.visibility.value = SHOWN_FOCUSED
        delay(300)

        assertEquals(0, poller.ticks.size)
        job.cancelAndJoin()
    }

    @Test
    fun `a tick that refreshed nothing waits a whole interval before the next`(): Unit = runBlocking {
        val poller = Poller(SHOWN_UNFOCUSED, interval = 200.milliseconds, lastRefreshAgo = null)
        // An operation runs
        poller.refreshes = false
        val job = with(poller) { start() }

        awaitTicks(poller, 3)
        job.cancelAndJoin()

        val ticks = poller.ticks
        assertTrue(ticks[1] - ticks[0] >= 195, "second tick after ${ticks[1] - ticks[0]} ms")
        assertTrue(ticks[2] - ticks[1] >= 195, "third tick after ${ticks[2] - ticks[1]} ms")
    }

    @Test
    fun `the settings' choices become intervals, and the settings file can't make them too short`() {
        assertEquals(null, WorktreesRefreshIntervals.pollInterval(0))
        assertEquals(null, WorktreesRefreshIntervals.pollInterval(-5))
        assertEquals(2.seconds, WorktreesRefreshIntervals.pollInterval(1))
        assertEquals(5.seconds, WorktreesRefreshIntervals.pollInterval(5))
        assertEquals(60.seconds, WorktreesRefreshIntervals.pollInterval(60))
        assertTrue(WorktreesRefreshIntervals.DEFAULT_SECONDS in WorktreesRefreshIntervals.CHOICES)
    }

    private suspend fun awaitTicks(poller: Poller, count: Int) = withTimeout(5_000) {
        while (poller.ticks.size < count) delay(5)
    }
}
