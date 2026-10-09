// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a tab's worktree list is on screen: the tab is selected, its side panel shows the Worktrees section expanded,
 * and the window isn't minimized. Also whether the window has the focus.
 */
data class WorktreesVisibility(val isShown: Boolean, val isWindowFocused: Boolean) {
    companion object {
        val Hidden = WorktreesVisibility(isShown = false, isWindowFocused = false)
    }
}

/** When the window gets the focus, the list refreshes unless its last refresh is younger than this. */
private val FOCUS_REFRESH_MIN_AGE = 1.seconds

/**
 * When to refresh the worktree list, which nothing watches in the other worktrees' working trees: every [interval]
 * while it's shown, also when the window doesn't have the focus, as agents work in another app. Nothing while it's
 * hidden or [interval] is null.
 *
 * Each tick comes [interval] after the list's last refresh, by this or anything else ([lastRefreshAt]), or after the
 * last tick when that one refreshed nothing. So the first one comes right away when the list is older than [interval],
 * as after the tab was in the background. When the window gets the focus while the list is shown, a tick comes right
 * away, unless the list was refreshed in the last second.
 *
 * Collect it with the refresh in the collector: a tick waits for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun worktreePollTicks(
    visibility: Flow<WorktreesVisibility>,
    interval: Flow<Duration?>,
    lastRefreshAt: () -> Long,
    now: () -> Long = System::currentTimeMillis,
): Flow<Unit> = flow {
    var previous: WorktreesVisibility? = null

    val ticks = combine(visibility, interval, ::Pair)
        .distinctUntilChanged()
        .transformLatest { (visibility, interval) ->
            val wasShown = previous?.isShown == true
            val focusGained = wasShown && visibility.isShown &&
                    previous?.isWindowFocused == false && visibility.isWindowFocused
            previous = visibility

            if (!visibility.isShown || interval == null) return@transformLatest

            val intervalMillis = interval.inWholeMilliseconds
            var tickRightAway = focusGained && now() - lastRefreshAt() >= FOCUS_REFRESH_MIN_AGE.inWholeMilliseconds
            var lastTickAt = 0L

            while (true) {
                val age = now() - maxOf(lastRefreshAt(), lastTickAt)

                if (tickRightAway || age >= intervalMillis) {
                    tickRightAway = false
                    lastTickAt = now()
                    emit(Unit)
                } else {
                    delay(intervalMillis - age)
                }
            }
        }

    emitAll(ticks)
}
