// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain

import dev.app.leaf.common.TabScope
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Runs a tab's data refreshes one at a time. They share the tab's [GraphLogGenerator], which isn't thread-safe, and
 * each sets its data to Loading, so two at once could leave the older result last. A call made while a refresh runs
 * waits, then refreshes what it asked for. Calls made while one already waits add what they ask for to that call's
 * refresh and return at once, so a burst of changes never queues more than one refresh. See [ConflatedRunner], which
 * does the same for work that takes no arguments.
 */
@TabScope
class DataRefreshRunner @Inject constructor() {
    private val mutex = Mutex()
    private val lock = Any()

    /** What the waiting call will refresh, or null when no call waits. Guarded by [lock]. */
    private var waiting: Set<DataToRefresh>? = null

    suspend fun run(dataToRefresh: Set<DataToRefresh>, refresh: suspend (Set<DataToRefresh>) -> Unit) {
        synchronized(lock) {
            val alreadyWaiting = waiting

            if (alreadyWaiting != null) {
                waiting = alreadyWaiting + dataToRefresh
                return
            }

            waiting = dataToRefresh
        }

        var isWaiting = true

        try {
            mutex.withLock {
                val toRefresh = synchronized(lock) {
                    isWaiting = false
                    checkNotNull(waiting).also { waiting = null }
                }

                refresh(toRefresh)
            }
        } finally {
            // Cancelled while waiting, which happens when the tab closes: the next call has to wait instead
            if (isWaiting) {
                synchronized(lock) { waiting = null }
            }
        }
    }

    /** Runs [block] between refreshes, for work that reads or changes what they load, such as loading more commits. */
    suspend fun <T> runAlone(block: suspend () -> T): T = mutex.withLock { block() }

    /** Whether a call waits for the current refresh to end. For tests, which can't tell otherwise. */
    internal fun hasCallWaiting(): Boolean = synchronized(lock) { waiting != null }
}
