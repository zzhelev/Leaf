// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchRegistrations"

/** Where macOS stops delivering events, without an error, for the registrations of every tab and app together. */
const val WATCH_LIMIT = 250

/** Leaf warns from here, as other apps use registrations too. */
const val WATCH_WARNING_AT = 200

/** How many folders the watchers hold, and whether that is near the system's limit. */
data class WatchUsage(val registrations: Int, val tabs: Int) {
    val isNearLimit: Boolean get() = registrations >= WATCH_WARNING_AT
}

/**
 * The registrations of every file watcher, counted for the whole app (fork-only). macOS stops delivering events past
 * about [WATCH_LIMIT] of them in all, and says nothing, so the bottom bar warns before that (`WatchLimitNotice`).
 * Other systems have limits that this app doesn't come near, so they're never near it.
 */
@Singleton
class WatchRegistrations(private val isLimited: Boolean) {
    @Inject
    constructor() : this(currentOs.isMac())

    private val perWatcher = ConcurrentHashMap<Any, Int>()

    private val _usage = MutableStateFlow(WatchUsage(registrations = 0, tabs = 0))
    val usage: StateFlow<WatchUsage> = _usage.asStateFlow()

    /** Notes how many folders [watcher] watches now. */
    fun update(watcher: Any, registrations: Int) {
        if (registrations > 0) perWatcher[watcher] = registrations else perWatcher.remove(watcher)

        publish()
    }

    /** [watcher] watches nothing any more. */
    fun release(watcher: Any) {
        perWatcher.remove(watcher)

        publish()
    }

    @Synchronized
    private fun publish() {
        val usage = WatchUsage(perWatcher.values.sum(), perWatcher.size)
        val wasNear = _usage.value.isNearLimit

        _usage.value = usage
        _warning.value = usage.takeIf { isLimited && it.isNearLimit }

        if (isLimited && usage.isNearLimit != wasNear) {
            if (usage.isNearLimit) {
                printError(
                    TAG,
                    "Watching ${usage.registrations} folders in ${usage.tabs} tabs, near macOS's limit of about " +
                        "$WATCH_LIMIT: changes in some folders may go unreported",
                )
            } else {
                printError(TAG, "Watching ${usage.registrations} folders, below the warning again")
            }
        }
    }

    private val _warning = MutableStateFlow<WatchUsage?>(null)

    /** What the bottom bar shows: the usage while it is near the limit, otherwise nothing. */
    val warning: StateFlow<WatchUsage?> = _warning.asStateFlow()
}
