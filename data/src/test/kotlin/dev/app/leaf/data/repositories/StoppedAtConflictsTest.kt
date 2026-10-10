// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IMergeBranchGitAction
import dev.app.leaf.domain.interfaces.IPullBranchGitAction
import dev.app.leaf.domain.interfaces.IRebaseBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.MergeBranchUseCase
import dev.app.leaf.domain.usecases.PullBranchUseCase
import dev.app.leaf.domain.usecases.RebaseBranchUseCase
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val GIT_DIR = "/repository/.git"

/**
 * A merge, rebase or pull that stopped at conflicts is recorded as such ([CompletedTask.Success.stoppedAtConflicts]),
 * so that its toast warns instead of saying it completed. The use cases run through a real [UseCaseExecutor], with
 * their git actions faked.
 */
class StoppedAtConflictsTest {
    private val dataRepository = InMemoryRepositoryDataRepository().apply {
        setRepositorySelectionState(RepositorySelectionState.Open(GIT_DIR))
    }
    private val stateRepository = InMemoryRepositoryStateRepository()
    private val settings = AppSettingsService(
        mockk<AppSettingsRepository> {
            every { autoStashOnMerge } returns flowOf(false)
            every { fastForwardMerge } returns flowOf(false)
            every { pullWithRebase } returns flowOf(false)
        }
    )

    private val executor: UseCaseExecutor = UseCaseExecutor(dataRepository, stateRepository, { refresh }, TabCoroutineScope())

    // What the tasks refresh afterwards only launches on this scope, which is cancelled, so nothing runs
    private val refresh: RefreshDataUseCase by lazy {
        testRefreshDataUseCase(executor, dataRepository, stateRepository, TabCoroutineScope().apply { cancel() })
    }

    private val branch = Branch("0".repeat(40), "refs/heads/feature", isLocal = true)

    @Test
    fun `a merge that stopped at conflicts is recorded as such, and one that didn't as completed`(): Unit =
        runBlocking {
            assertEquals(listOf(true, false), listOf(true, false).map { hasConflicts ->
                val merge = mockk<IMergeBranchGitAction>()
                coEvery { merge(GIT_DIR, branch, false) } returns Either.Ok(hasConflicts)

                lastTask(TaskType.MergeBranch) {
                    MergeBranchUseCase(merge, settings, mockk(), executor, mockk(), mockk())(branch, "stash")
                }
            })
        }

    @Test
    fun `a rebase that stopped at conflicts is recorded as such, and one that didn't as completed`(): Unit =
        runBlocking {
            assertEquals(listOf(true, false), listOf(true, false).map { hasStopped ->
                val rebase = mockk<IRebaseBranchGitAction>()
                coEvery { rebase(GIT_DIR, branch) } returns Either.Ok(hasStopped)

                lastTask(TaskType.RebaseBranch) { RebaseBranchUseCase(executor, rebase)(branch) }
            })
        }

    @Test
    fun `a pull that stopped at conflicts is recorded as such, and one that didn't as completed`(): Unit =
        runBlocking {
            assertEquals(listOf(true, false), listOf(true, false).map { hasConflicts ->
                val pull = mockk<IPullBranchGitAction>()
                coEvery { pull(GIT_DIR, PullType.MERGE, false, null, "stash") } returns Either.Ok(hasConflicts)

                lastTask(TaskType.Pull) {
                    PullBranchUseCase(executor, pull, settings)(PullType.DEFAULT, automaticStashDescription = "stash")
                    Unit
                }
            })
        }

    /** Starts a task with [launch], and returns whether it was recorded as stopped at conflicts. */
    private suspend fun lastTask(taskType: TaskType, launch: () -> Unit): Boolean {
        val before = stateRepository.completedTasks.value.size

        launch()

        val task = withTimeout(5_000) { stateRepository.completedTasks.first { it.size > before } }.last()
        assertEquals(taskType, task.taskType)

        return (task as CompletedTask.Success).stoppedAtConflicts
    }
}
