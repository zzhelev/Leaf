// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.remote

import dev.app.leaf.domain.models.ServerHandOff
import dev.app.leaf.domain.models.TaskProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The processing screen's button: Cancel until git hands the server a push, then none, then Stop waiting. */
class StopWaitingTest {
    private val handOff = ServerHandOff("origin", "refs/heads/main", "refs/remotes/origin/main", "a".repeat(40), null)

    @Test
    fun `a task that reports no progress has no button`() {
        assertNull(processingButton(progress = null, nowMillis = 0))
    }

    @Test
    fun `a task that hasn't handed anything to the server can be cancelled`() {
        assertEquals(ProcessingButton.CANCEL, processingButton(TaskProgress("Writing objects", 45), nowMillis = 0))
        assertEquals(ProcessingButton.CANCEL, processingButton(TaskProgress(null, null), nowMillis = 1_000_000))
    }

    @Test
    fun `once the server has it, there is no button until it has had it for ten seconds`() {
        val progress = TaskProgress(null, null, handOff, handedOverAt = 1_000)

        assertNull(processingButton(progress, nowMillis = 1_000))
        assertNull(processingButton(progress, nowMillis = 1_000 + STOP_WAITING_AFTER.inWholeMilliseconds - 1))
        assertEquals(
            ProcessingButton.STOP_WAITING,
            processingButton(progress, nowMillis = 1_000 + STOP_WAITING_AFTER.inWholeMilliseconds),
        )
        // A clock that went back shows none, rather than Cancel
        assertNull(processingButton(progress, nowMillis = 0))
    }

    @Test
    fun `the server's own progress after the hand-off doesn't bring Cancel back`() {
        val progress = TaskProgress("Resolving deltas", 50, handOff, handedOverAt = 1_000)

        assertNull(processingButton(progress, nowMillis = 2_000))
        assertEquals(ProcessingButton.STOP_WAITING, processingButton(progress, nowMillis = 20_000))
    }
}
