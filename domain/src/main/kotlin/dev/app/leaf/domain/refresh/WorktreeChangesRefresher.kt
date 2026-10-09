// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.domain.ConflatedRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Refreshes the worktree list when the file watcher sees another worktree change: right away, then at most once every
 * [minGap] while changes keep coming. An agent that runs git in its worktree rewrites that worktree's index all the
 * time, and each refresh runs git in every worktree.
 */
class WorktreeChangesRefresher(
    private val scope: CoroutineScope,
    private val minGap: Duration,
    private val refresh: suspend () -> Unit,
) {
    private val runner = ConflatedRunner()

    fun onChanged() {
        scope.launch {
            runner.run {
                refresh()
                delay(minGap)
            }
        }
    }
}
