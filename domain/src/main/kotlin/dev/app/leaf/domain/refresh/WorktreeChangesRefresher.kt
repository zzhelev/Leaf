// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.domain.ConflatedRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Refreshes the worktree list when the file watcher sees another worktree change: right away, then at most once every
 * [minGap] while changes keep coming. An agent that runs git in its worktree rewrites that worktree's index all the
 * time, and each refresh runs git in every worktree.
 *
 * A change that moves another worktree's HEAD also needs the log (it starts from a detached HEAD's commit), and asks for
 * it with `onChanged(includesLog = true)`. It doesn't wait for another gap: [refresh] is told whether any call since the
 * last refresh asked, so that one refresh does both, and index churn never refreshes the log.
 *
 * @param refresh refreshes the worktree list, and the log too when [includesLog] is true.
 */
class WorktreeChangesRefresher(
    private val scope: CoroutineScope,
    private val minGap: Duration,
    private val refresh: suspend (includesLog: Boolean) -> Unit,
) {
    private val runner = ConflatedRunner()
    private val logWanted = AtomicBoolean(false)

    fun onChanged(includesLog: Boolean = false) {
        // Before the call that may return at once, as a waiting one starts after this
        if (includesLog) logWanted.set(true)

        scope.launch {
            runner.run {
                refresh(logWanted.getAndSet(false))
                delay(minGap)
            }
        }
    }
}
