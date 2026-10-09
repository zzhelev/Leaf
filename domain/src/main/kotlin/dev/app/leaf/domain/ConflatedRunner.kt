// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain

import dev.app.leaf.common.TabScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Runs a block one call at a time, for work whose latest result is all that matters, such as a refresh. A call made
 * while the block runs waits, then runs it once more, so its result reflects what happened before the call. Calls made
 * while one already waits return at once: that one's run starts after them too.
 */
open class ConflatedRunner(private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val isCallWaiting = AtomicBoolean(false)

    /** When the last run ended, failed or cancelled, in milliseconds from [clock]. 0 before the first one. */
    @Volatile
    var lastRunEndedAt: Long = 0
        private set

    suspend fun run(block: suspend () -> Unit) {
        if (!isCallWaiting.compareAndSet(false, true)) return

        var isWaiting = true

        try {
            mutex.withLock {
                isCallWaiting.set(false)
                isWaiting = false

                try {
                    block()
                } finally {
                    lastRunEndedAt = clock()
                }
            }
        } finally {
            // Cancelled while waiting: the next call has to wait instead
            if (isWaiting) {
                isCallWaiting.set(false)
            }
        }
    }

    /** Whether a call waits for the current run to end. For tests, which can't tell otherwise. */
    internal fun hasCallWaiting(): Boolean = isCallWaiting.get()
}

/** Runs a tab's worktree refreshes, which start git in every worktree, one at a time. See [ConflatedRunner]. */
@TabScope
class WorktreesRefreshRunner @Inject constructor() : ConflatedRunner()
