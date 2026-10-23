// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.remote

import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.HandOffOutcome
import dev.app.leaf.domain.interfaces.ICheckHandOffGitAction
import dev.app.leaf.domain.models.ServerHandOff
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Asking the server what it did after the user stopped waiting ([StoppedWaitingCheck]), with a server whose answers a
 * test writes. The server doing it, which refreshes the tab, is in the data module's `StoppingPushesTest`.
 */
class StoppedWaitingCheckTest {
    private val scope = TabCoroutineScope()
    private val repositoryData = mockk<RepositoryDataRepository> { every { repositoryPath } returns "/repo/.git" }
    private val executor = UseCaseExecutor(repositoryData, mockk(), Provider { error("nothing to refresh") }, scope)
    private val server = ScriptedServer()
    private val handOff = ServerHandOff("origin", "refs/heads/main", "refs/remotes/origin/main", "b".repeat(40), "a".repeat(40))

    private val check = StoppedWaitingCheck(
        server,
        executor,
        Provider<RefreshDataUseCase> { error("nothing to refresh") },
        scope,
    ).apply {
        checkAgainEvery = 10.milliseconds
        rounds = 4
    }

    @AfterEach
    fun cancelScope(): Unit = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `while the server hasn't done it, it asks again, and says so once the rounds end`(): Unit = runBlocking {
        server.answers += List(4) { Either.Ok(HandOffOutcome.NotDone("a".repeat(40))) }

        check.start(handOff)

        assertEquals(StoppedWaitingState.Asking(handOff), check.state.value)
        assertEquals(StoppedWaitingState.NotDone(handOff, "a".repeat(40)), awaitAnswer())
        assertEquals(4, server.asked.size)
    }

    @Test
    fun `a remote with several push URLs can't tell, and isn't asked again`(): Unit = runBlocking {
        server.answers += Either.Ok(HandOffOutcome.CantTell)

        check.start(handOff)

        assertEquals(StoppedWaitingState.Unknown(handOff), awaitAnswer())
        delay(100)
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `a server that can't be asked at first is a failure, and asking again starts over`(): Unit = runBlocking {
        val error = GenericError("Could not resolve host")
        server.answers += Either.Err(error)

        check.start(handOff)

        assertEquals(StoppedWaitingState.Failed(handOff, error), awaitAnswer())

        server.answers += List(4) { Either.Ok(HandOffOutcome.NotDone(null)) }
        check.tryAgain()

        assertEquals(StoppedWaitingState.Asking(handOff), check.state.value)
        assertEquals(StoppedWaitingState.NotDone(handOff, null), awaitAnswer())
    }

    @Test
    fun `asked again, a failure leaves the last answer`(): Unit = runBlocking {
        server.answers += Either.Ok(HandOffOutcome.NotDone("c".repeat(40)))
        server.answers += Either.Err(GenericError("Connection reset"))

        check.start(handOff)

        assertEquals(StoppedWaitingState.NotDone(handOff, "c".repeat(40)), awaitAnswer())
        assertEquals(2, server.asked.size)
    }

    @Test
    fun `stopping the check before an answer says nothing tells, and after one keeps it`(): Unit = runBlocking {
        val held = CompletableDeferred<Unit>()
        server.hold = held

        check.start(handOff)
        withTimeout(5.seconds) { while (server.asked.isEmpty()) delay(5) }
        check.stopChecking()

        assertEquals(StoppedWaitingState.Unknown(handOff), awaitAnswer())

        server.hold = null
        server.answers += Either.Ok(HandOffOutcome.NotDone("a".repeat(40)))
        check.checkAgainEvery = 10.seconds
        check.tryAgain()
        withTimeout(5.seconds) { while (server.asked.size < 2) delay(5) }
        delay(50)
        check.stopChecking()

        assertEquals(StoppedWaitingState.NotDone(handOff, "a".repeat(40)), awaitAnswer())
    }

    @Test
    fun `dismissing it stops asking, and nothing comes back`(): Unit = runBlocking {
        server.hold = CompletableDeferred()

        check.start(handOff)
        withTimeout(5.seconds) { while (server.asked.isEmpty()) delay(5) }
        check.dismiss()
        delay(100)

        assertNull(check.state.value)
    }

    @Test
    fun `asking about another hand-off replaces the one shown`(): Unit = runBlocking {
        server.hold = CompletableDeferred()
        check.start(handOff)
        withTimeout(5.seconds) { while (server.asked.isEmpty()) delay(5) }

        val delete = ServerHandOff("origin", "refs/heads/old", "refs/remotes/origin/old", pushed = null, before = "d".repeat(40))
        server.hold = null
        server.answers += List(4) { Either.Ok(HandOffOutcome.NotDone("d".repeat(40))) }
        check.start(delete)

        assertEquals(StoppedWaitingState.NotDone(delete, "d".repeat(40)), awaitAnswer())
    }

    private suspend fun awaitAnswer(): StoppedWaitingState? = withTimeout(5.seconds) {
        check.state.first { it !is StoppedWaitingState.Asking }
    }

    /** Answers in turn from [answers], after [hold] completes when it's set; [asked] has each question. */
    private class ScriptedServer : ICheckHandOffGitAction {
        val answers: MutableList<Either<HandOffOutcome, GitError>> = CopyOnWriteArrayList()
        val asked: MutableList<ServerHandOff> = CopyOnWriteArrayList()

        @Volatile
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun invoke(repositoryPath: String, handOff: ServerHandOff): Either<HandOffOutcome, GitError> {
            asked += handOff
            hold?.await()

            return answers.removeAt(0)
        }
    }
}
