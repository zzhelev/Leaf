// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.DataRefreshRunner
import dev.app.leaf.domain.GraphLogGenerator
import dev.app.leaf.domain.GraphRevWalker
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.WorktreesRefreshRunner
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetBranchesGitAction
import dev.app.leaf.domain.interfaces.IGetCommitTimesGitAction
import dev.app.leaf.domain.interfaces.IGetCurrentBranchGitAction
import dev.app.leaf.domain.interfaces.IGetPersistedCommitMessagesGitAction
import dev.app.leaf.domain.interfaces.IGetRemoteBranchesGitAction
import dev.app.leaf.domain.interfaces.IGetRemotesGitAction
import dev.app.leaf.domain.interfaces.IGetStashListGitAction
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.interfaces.IGetTagsGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeBaseBranchGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreesGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.PersistedCommitMessage
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.usecases.GetLogUseCase
import dev.app.leaf.domain.usecases.GetRebaseInteractiveTodoLinesUseCase
import dev.app.leaf.domain.usecases.GetRebaseLinesFullMessageUseCase
import dev.app.leaf.domain.usecases.GetRemotesUseCase
import dev.app.leaf.domain.usecases.GetWorktreesInfoUseCase
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import io.mockk.coEvery
import io.mockk.mockk
import javax.inject.Provider

/** A log without commits. */
private class NoCommits : GraphRevWalker {
    override suspend fun prepare(repository: String, startingCommits: List<String>) = Either.Ok(Unit)
    override fun iterator(): Iterator<Commit> = emptyList<Commit>().iterator()
    override fun close() = Unit
}

/**
 * Builds a tab's [RefreshDataUseCase] around [executor], whose refreshes of the status, the log and the worktrees
 * find an empty repository, with the status from [getStatus]. The other git actions are MockK mocks that fail when
 * called. MockK can't mock the use case itself: it's a final class.
 */
fun testRefreshDataUseCase(
    executor: UseCaseExecutor,
    dataRepository: RepositoryDataRepository,
    stateRepository: RepositoryStateRepository,
    scope: TabCoroutineScope,
    getStatus: IGetStatusGitAction = mockk(),
): RefreshDataUseCase {
    val getBranches = mockk<IGetBranchesGitAction> { coEvery { this@mockk(any<String>()) } returns Either.Ok(emptyList()) }
    val getCurrentBranch = mockk<IGetCurrentBranchGitAction> {
        coEvery { this@mockk(any<String>()) } returns Either.Ok(null)
    }
    val getTags = mockk<IGetTagsGitAction> { coEvery { this@mockk(any()) } returns Either.Ok(emptyList()) }
    val getStashes = mockk<IGetStashListGitAction> { coEvery { this@mockk(any()) } returns Either.Ok(emptyList()) }
    val getRemotes = mockk<IGetRemotesGitAction> { coEvery { this@mockk(any()) } returns Either.Ok(emptyList()) }
    val getRemoteBranches = mockk<IGetRemoteBranchesGitAction> {
        coEvery { this@mockk(any()) } returns Either.Ok(emptyList())
    }
    val getCommitMessages = mockk<IGetPersistedCommitMessagesGitAction> {
        coEvery { this@mockk(any()) } returns Either.Ok(PersistedCommitMessage(null, null, null))
    }
    val getWorktrees = mockk<IGetWorktreesGitAction> { coEvery { this@mockk(any()) } returns Either.Ok(emptyList()) }
    val getBaseBranch = mockk<IGetWorktreeBaseBranchGitAction> {
        coEvery { this@mockk(any()) } returns Either.Ok(WorktreeBaseBranch(automatic = null))
    }
    val getCommitTimes = mockk<IGetCommitTimesGitAction> {
        coEvery { this@mockk(any(), any()) } returns Either.Ok(emptyMap())
    }

    val getRemotesUseCase = GetRemotesUseCase(getRemotes, getRemoteBranches, executor)

    return RefreshDataUseCase(
        useCaseExecutor = executor,
        getBranchesGitAction = getBranches,
        getCurrentBranchGitAction = getCurrentBranch,
        repositoryDataRepository = dataRepository,
        repositoryStateRepository = stateRepository,
        getStashListGitAction = getStashes,
        loadAuthorGitAction = mockk(),
        getStatusGitAction = getStatus,
        getRemotesUseCase = getRemotesUseCase,
        getSubmodulesGitAction = mockk(),
        getTagsGitAction = getTags,
        getRefDatesGitAction = mockk(),
        getRepositoryState = mockk(),
        getRebaseInteractiveTodoLinesUseCase = GetRebaseInteractiveTodoLinesUseCase(mockk(), executor),
        getRebaseLinesFullMessageUseCase = GetRebaseLinesFullMessageUseCase(mockk(), executor),
        getPersistedCommitMessagesGitAction = getCommitMessages,
        getLogUseCase = GetLogUseCase(
            getBranches,
            getTags,
            getRemotesUseCase,
            getStashes,
            getStatus,
            getCurrentBranch,
            GraphLogGenerator(Provider { NoCommits() }),
            dataRepository,
        ),
        getWorktreesInfoUseCase = GetWorktreesInfoUseCase(getWorktrees, mockk(), mockk(), getBaseBranch, getCommitTimes, executor),
        worktreesRefreshRunner = WorktreesRefreshRunner(),
        dataRefreshRunner = DataRefreshRunner(),
        scope = scope,
    )
}
