// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.repositories.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val GIT_DIR = "/repo/.git"

/** How [InMemoryRepositoryDataRepository] records what a refresh step loaded. */
class RepositoryDataLoadingTest {
    private val repository = InMemoryRepositoryDataRepository()

    // A refresh writes only while the tab shows the git dir it read
    @BeforeEach
    fun openRepository() {
        repository.setRepositorySelectionState(RepositorySelectionState.Open(GIT_DIR))
    }

    @Test
    fun `a step that throws leaves an error, not Loading`(): Unit = runBlocking {
        val exception = IllegalStateException("RevWalk can not be null, make sure to call `prepare`.")

        repository.updateLog(GIT_DIR) { throw exception }

        val state = assertInstanceOf(DataState.Error::class.java, repository.log.value)
        val error = assertInstanceOf(GenericError::class.java, state.error)
        assertSame(exception, error.exception)
    }

    @Test
    fun `a step that fails or succeeds is recorded as before`(): Unit = runBlocking {
        val failure = GenericError("Not found")
        repository.updateLog(GIT_DIR) { Either.Err(failure) }
        assertEquals(DataState.Error(failure), repository.log.value)

        val log = GraphCommits()
        repository.updateLog(GIT_DIR) { Either.Ok(log) }
        assertEquals(DataState.Loaded(log), repository.log.value)
    }

    @Test
    fun `a step that is cancelled stays cancelled`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var thrown: Throwable? = null

        val refresh = launch {
            try {
                repository.updateLog(GIT_DIR) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }

        started.await()
        refresh.cancelAndJoin()

        assertInstanceOf(CancellationException::class.java, thrown)
        assertEquals(DataState.Loading, repository.log.value)
    }
}
