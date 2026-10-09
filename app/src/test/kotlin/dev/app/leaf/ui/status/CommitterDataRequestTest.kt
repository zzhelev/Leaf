// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import dev.app.leaf.domain.models.AuthorInfo
import dev.app.leaf.domain.models.Identity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

class CommitterDataRequestTest {
    private val author = AuthorInfo(Identity(null, null), Identity(null, null))

    @Test
    fun `waiting for the identity dialog leaves the thread free`(): Unit = runBlocking {
        val state = MutableStateFlow<CommitterDataRequestState>(CommitterDataRequestState.WaitingInput(author))
        // Stands in for the view model's dispatcher, which has as few threads as the machine has cores
        val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        try {
            val answer = async(thread) { state.awaitAnswer() }

            // Queued after the wait, so it only runs if the wait doesn't hold the thread
            val otherWorkRan = CompletableDeferred<Unit>()
            launch(thread) { otherWorkRan.complete(Unit) }
            val ran = withTimeoutOrNull(5.seconds) { otherWorkRan.await() }

            val accepted = CommitterDataRequestState.Accepted(author, persist = false)
            state.value = accepted

            assertNotNull(ran, "Other work never ran while the dialog was open")
            assertEquals(accepted, withTimeout(5.seconds) { answer.await() })
        } finally {
            thread.close()
        }
    }

    @Test
    fun `the answer can be a rejection`(): Unit = runBlocking {
        val state = MutableStateFlow<CommitterDataRequestState>(CommitterDataRequestState.WaitingInput(author))
        val answer = async { state.awaitAnswer() }

        state.value = CommitterDataRequestState.Reject

        assertEquals(CommitterDataRequestState.Reject, withTimeout(5.seconds) { answer.await() })
    }
}
