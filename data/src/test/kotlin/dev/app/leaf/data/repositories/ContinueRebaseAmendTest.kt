// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IContinueRebaseGitAction
import dev.app.leaf.domain.interfaces.IDoCommitGitAction
import dev.app.leaf.domain.interfaces.ILoadAuthorGitAction
import dev.app.leaf.domain.interfaces.ILoadSignOffConfigGitAction
import dev.app.leaf.domain.interfaces.IPersistCommitMessageGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.SignOffConfig
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.usecases.ContinueRebaseUseCase
import dev.app.leaf.domain.usecases.DoCommitUseCase
import dev.app.leaf.domain.usecases.GetAuthorUseCase
import dev.app.leaf.domain.usecases.PersistCommitMessageUseCase
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

/** "Continue" on a rebase step marked to amend: [ContinueRebaseUseCase] amends, then continues. */
class ContinueRebaseAmendTest {
    private val repositoryPath = "/repository/.git"
    private val identity = Identity("Someone", "someone@example.com")
    private val stateRepository = InMemoryRepositoryStateRepository()
    private val scope = TabCoroutineScope()

    /** What ran, in order, with the foreground task at that moment. */
    private val steps: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val dataRepository = mockk<RepositoryDataRepository> {
        every { repositoryPath } returns this@ContinueRebaseAmendTest.repositoryPath
    }

    /** Refreshes nothing when the rebase asks: its scope is cancelled, so its coroutine never starts. */
    private val refreshNothing: RefreshDataUseCase by lazy {
        testRefreshDataUseCase(executor, dataRepository, stateRepository, TabCoroutineScope().apply { cancel() })
    }

    private val executor = UseCaseExecutor(dataRepository, stateRepository, Provider { refreshNothing }, scope)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun continueRebase(amendResult: Either<Commit, GenericError>): ContinueRebaseUseCase {
        val doCommit = object : IDoCommitGitAction {
            override suspend fun invoke(
                repositoryPath: String,
                message: String,
                amend: Boolean,
                author: Identity?,
            ): Either<Commit, GitError> {
                // Slower than continuing, so that an amend nobody waits for ends last
                delay(200)
                steps.add("amend '$message' (amend=$amend) during ${stateRepository.currentTask.value}")
                return amendResult
            }
        }

        val continueRebase = object : IContinueRebaseGitAction {
            override suspend fun invoke(repositoryPath: String): Either<Unit, GitError> {
                steps.add("continue during ${stateRepository.currentTask.value}")
                return Either.Ok(Unit)
            }
        }

        val signOffDisabled = object : ILoadSignOffConfigGitAction {
            override suspend fun invoke(repositoryPath: String) = Either.Ok(SignOffConfig(isEnabled = false, format = ""))
        }

        val persistMessage = object : IPersistCommitMessageGitAction {
            override suspend fun invoke(repositoryPath: String, message: String?) = Either.Ok(Unit)
        }

        val doCommitUseCase = DoCommitUseCase(
            doCommit,
            executor,
            signOffDisabled,
            GetAuthorUseCase(mockk<ILoadAuthorGitAction>(), executor),
            PersistCommitMessageUseCase(persistMessage, executor),
        )

        return ContinueRebaseUseCase(executor, continueRebase, doCommitUseCase)
    }

    private suspend fun runContinueRebase(amendResult: Either<Commit, GenericError>) {
        continueRebase(amendResult)(
            message = "Amended message",
            isAmendRebaseInteractive = true,
            repositoryState = RepositoryState.REBASING_INTERACTIVE,
            rebaseInteractiveState = RebaseInteractiveState.ProcessingCommits(commitToAmendId = "abc1234"),
            onIdentityRequest = { identity },
        )

        withTimeout(5.seconds) {
            stateRepository.completedTasks.first { it.isNotEmpty() }
            // A task of the amend's own would come after
            delay(400)
        }
    }

    @Test
    fun `amends before continuing, within the same task`(): Unit = runBlocking {
        runContinueRebase(Either.Ok(Commit("def5678", "Amended message", identity, identity, 0, emptyList())))

        assertEquals(
            listOf(
                "amend 'Amended message' (amend=true) during ${TaskType.ContinueRebase}",
                "continue during ${TaskType.ContinueRebase}",
            ),
            steps,
        )
        assertEquals(
            listOf(TaskType.ContinueRebase),
            stateRepository.completedTasks.value.map { it.taskType },
        )
        assertEquals(CompletedTask.Success::class, stateRepository.completedTasks.value.single()::class)
    }

    @Test
    fun `doesn't continue when the amend fails, and shows its error`(): Unit = runBlocking {
        val error = GenericError("Amend refused")

        runContinueRebase(Either.Err(error))

        assertEquals(listOf("amend 'Amended message' (amend=true) during ${TaskType.ContinueRebase}"), steps)
        val failure = stateRepository.completedTasks.value.single() as CompletedTask.Failure
        assertEquals(TaskType.ContinueRebase, failure.taskType)
        assertEquals(error, failure.reason)
    }
}
