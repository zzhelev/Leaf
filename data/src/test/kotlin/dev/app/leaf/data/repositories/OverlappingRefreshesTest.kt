// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

/** What an agent working in the tab's worktree causes: a refresh of the status and the log every few hundred ms. */
class OverlappingRefreshesTest {
    private val dataRepository = InMemoryRepositoryDataRepository().apply {
        setRepositorySelectionState(RepositorySelectionState.Open("/repository/.git"))
    }
    private val stateRepository = InMemoryRepositoryStateRepository()
    private val scope = TabCoroutineScope()

    private val firstStatusRead = CompletableDeferred<Unit>()
    private val finishFirstStatusRead = CompletableDeferred<Unit>()
    private val statusReads = AtomicInteger()
    private val running = AtomicInteger()
    private val mostAtOnce = AtomicInteger()

    private val getStatus = object : IGetStatusGitAction {
        override suspend fun invoke(repository: String, paths: List<String>): Either<Status, AppError> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }

            try {
                if (statusReads.incrementAndGet() == 1) {
                    firstStatusRead.complete(Unit)
                    finishFirstStatusRead.await()
                } else {
                    delay(10)
                }
            } finally {
                running.decrementAndGet()
            }

            return Either.Ok(Status())
        }
    }

    private val refreshData: RefreshDataUseCase by lazy {
        testRefreshDataUseCase(executor, dataRepository, stateRepository, scope, getStatus)
    }

    private val executor = UseCaseExecutor(dataRepository, stateRepository, Provider { refreshData }, scope)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a burst of refreshes runs one at a time, and only once more`(): Unit = runBlocking {
        withTimeout(10.seconds) {
            val first = refreshData(DataToRefresh.STATUS, DataToRefresh.LOG)
            firstStatusRead.await()

            val others = (1..9).map { refreshData(DataToRefresh.STATUS, DataToRefresh.LOG) }

            // One waits for the first; the others add to its refresh and end at once
            while (others.count { it.isCompleted } < 8) yield()

            finishFirstStatusRead.complete(Unit)
            (others + first).joinAll()
        }

        assertEquals(1, mostAtOnce.get())
        // Each refresh reads the status twice: for the status, and for the log, which keeps a lane for local changes
        assertEquals(4, statusReads.get())
        assertEquals(DataState.Loaded(Status()), dataRepository.status.first())
        assertEquals(DataState.Loaded::class, dataRepository.log.value::class)
    }
}
