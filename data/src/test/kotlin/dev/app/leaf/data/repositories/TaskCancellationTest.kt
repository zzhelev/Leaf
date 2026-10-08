// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** The processing screen's Cancel button: [InMemoryRepositoryStateRepository.cancelCurrentTask] and [UseCaseExecutor]. */
class TaskCancellationTest {
    private val stateRepository = InMemoryRepositoryStateRepository()
    private val executor = UseCaseExecutor(
        mockk<RepositoryDataRepository> { every { repositoryPath } returns "/repository/.git" },
        stateRepository,
        Provider { error("Nothing is refreshed") },
        TabCoroutineScope(),
    )

    @Test
    fun `a task that reports progress stops when cancelled, and no failure is shown`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()

        val task = executor.executeLaunch(TaskType.Push, dataToRefresh = emptyArray()) {
            stateRepository.updateTaskProgress(TaskProgress(stage = null, percent = null))
            started.complete(Unit)

            // Like JGit.provide, which turns every exception into an error, cancellation included
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                Either.Err(GenericError("Cancelled"))
            }
        }

        started.await()
        stateRepository.cancelCurrentTask()
        withTimeout(5.seconds) { task.join() }

        assertTrue(task.isCancelled)
        assertEquals(emptyList<CompletedTask>(), stateRepository.completedTasks.value)
        assertNull(stateRepository.currentTask.value)
        assertNull(stateRepository.taskProgress.value)
    }

    @Test
    fun `a task without progress can't be cancelled`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val task = executor.executeLaunch(TaskType.Push, dataToRefresh = emptyArray()) {
            started.complete(Unit)
            release.await()
            Either.Ok(Unit)
        }

        started.await()
        stateRepository.cancelCurrentTask()
        release.complete(Unit)
        withTimeout(5.seconds) { task.join() }

        assertFalse(task.isCancelled)
        assertInstanceOf(CompletedTask.Success::class.java, stateRepository.completedTasks.value.single())
    }

    @Test
    fun `a task that fails is still shown as failed`(): Unit = runBlocking {
        val task = executor.executeLaunch(TaskType.Push, dataToRefresh = emptyArray()) {
            stateRepository.updateTaskProgress(TaskProgress(stage = "Writing objects", percent = 50))
            Either.Err(GenericError("Refused"))
        }

        withTimeout(5.seconds) { task.join() }

        val failure = stateRepository.completedTasks.value.single() as CompletedTask.Failure
        assertEquals(GenericError("Refused"), failure.reason)
        assertNull(stateRepository.taskProgress.value)
    }
}
