// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scroll state that a view model makes off the UI thread can be read by a frame that began before it was made, as
 * the diff pane's was when Leaf crashed after the file's last unstaged line was discarded (fork-only).
 */
class ScrollStatesTest {
    @Test
    fun `a frame that began before the state was made on another thread can read it`() {
        val frame = Snapshot.takeSnapshot()

        try {
            val state = onOtherThread { newLazyListState() }

            assertEquals(0, frame.enter { state.layoutInfo.totalItemsCount })
            assertEquals(0, frame.enter { state.firstVisibleItemIndex })
        } finally {
            frame.dispose()
        }
    }

    /** Why [newLazyListState] exists: the crash, with a state made the plain way. */
    @Test
    fun `a state made the plain way on another thread can't be read by that frame`() {
        val frame = Snapshot.takeSnapshot()

        try {
            val state = onOtherThread { LazyListState(0, 0) }
            val error = runCatching { frame.enter { state.layoutInfo } }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().startsWith("Reading a state that was created after"), error.toString())
        } finally {
            frame.dispose()
        }
    }

    private fun <T> onOtherThread(block: () -> T): T {
        var result: Result<T>? = null
        Thread { result = runCatching(block) }.apply { start(); join() }

        return result!!.getOrThrow()
    }
}
