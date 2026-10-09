package dev.app.leaf.data.repositories

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class InMemoryRepositoryDataRepository @Inject constructor() : RepositoryDataRepository {
    override val status: Flow<DataState<Status>>
        field = MutableStateFlow<DataState<Status>>(DataState.Loading)

    override val localBranches: Flow<DataState<List<Branch>>>
        field = MutableStateFlow<DataState<List<Branch>>>(DataState.Loading)

    override val currentBranch: Flow<DataState<Branch?>>
        field = MutableStateFlow<DataState<Branch?>>(DataState.Loading)

    override val tags: Flow<DataState<List<Tag>>>
        field = MutableStateFlow<DataState<List<Tag>>>(DataState.Loading)

    override val remotes: Flow<DataState<List<RemoteInfo>>>
        field = MutableStateFlow<DataState<List<RemoteInfo>>>(DataState.Loading)

    override val refDates: Flow<DataState<RefDates>>
        field = MutableStateFlow<DataState<RefDates>>(DataState.Loading)

    override val log: StateFlow<DataState<GraphCommits>>
        field = MutableStateFlow<DataState<GraphCommits>>(DataState.Loading)

    override val stashes: Flow<DataState<List<Commit>>>
        field = MutableStateFlow<DataState<List<Commit>>>(DataState.Loading)

    override val submodules: Flow<DataState<Map<String, Submodule>>>
        field = MutableStateFlow<DataState<Map<String, Submodule>>>(DataState.Loading)

    override val repositorySelectionState: StateFlow<RepositorySelectionState>
        field = MutableStateFlow<RepositorySelectionState>(RepositorySelectionState.Unknown)

    override val repositoryState: StateFlow<DataState<RepositoryState>>
        field = MutableStateFlow<DataState<RepositoryState>>(DataState.Loading)

    override val rebaseInteractiveState: StateFlow<DataState<RebaseInteractiveState>>
        field = MutableStateFlow<DataState<RebaseInteractiveState>>(DataState.Loading)

    override val author: Flow<DataState<AuthorInfo>>
        field = MutableStateFlow<DataState<AuthorInfo>>(DataState.Loading)

    override val persistedCommitMessage: Flow<DataState<PersistedCommitMessage>>
        field = MutableStateFlow<DataState<PersistedCommitMessage>>(DataState.Loading)

    override val worktrees: Flow<DataState<WorktreeList>>
        field = MutableStateFlow<DataState<WorktreeList>>(DataState.Loading)

    override var maxCommitsToLoadLimit: Int = 0

    override val repositoryPath: String?
        get() {
            return when (val state = repositorySelectionState.value) {
                is RepositorySelectionState.Open -> state.path
                else -> null
            }
        }

    override fun setRepositorySelectionState(state: RepositorySelectionState) {
        repositorySelectionState.value = state
    }

    override fun clearAll() {
        localBranches.value = DataState.Loading
        tags.value = DataState.Loading
        remotes.value = DataState.Loading
        refDates.value = DataState.Loading
        log.value = DataState.Loading
        stashes.value = DataState.Loading
        rebaseInteractiveState.value = DataState.Loading
        submodules.value = DataState.Loading
        worktrees.value = DataState.Loading
    }

    override suspend fun updateStatus(block: suspend () -> Either<Status, AppError>) {
        handleDataState(status, block)
    }

    override suspend fun updateLocalBranches(block: suspend () -> Either<List<Branch>, AppError>) {
        handleDataState(localBranches, block)
    }

    override suspend fun updateCurrentBranch(block: suspend () -> Either<Branch?, AppError>) {
        handleDataState(currentBranch, block)
    }

    override suspend fun updateTags(block: suspend () -> Either<List<Tag>, AppError>) {
        handleDataState(tags, block)
    }

    override suspend fun updateLog(block: suspend () -> Either<GraphCommits, AppError>) {
        handleDataState(log, block)
    }

    override suspend fun updateRemotes(block: suspend () -> Either<List<RemoteInfo>, AppError>) {
        handleDataState(remotes, block)
    }

    override suspend fun updateRefDates(block: suspend () -> Either<RefDates, AppError>) {
        handleDataState(refDates, block)
    }

    override suspend fun updateStashes(block: suspend () -> Either<List<Commit>, AppError>) {
        handleDataState(stashes, block)
    }

    override suspend fun updateSubmodules(block: suspend () -> Either<Map<String, Submodule>, AppError>) {
        handleDataState(submodules, block)
    }

    override suspend fun updateAuthor(block: suspend () -> Either<AuthorInfo, AppError>) {
        handleDataState(author, block)
    }

    override suspend fun updateRepositoryState(block: suspend () -> Either<RepositoryState, AppError>) {
        handleDataState(repositoryState, block)
    }

    override suspend fun updateRebaseInteractiveState(block: suspend () -> Either<RebaseInteractiveState, AppError>) {
        handleDataState(rebaseInteractiveState, block)
    }

    override suspend fun updatePersistedCommitMessages(block: suspend () -> Either<PersistedCommitMessage, AppError>) {
        handleDataState(persistedCommitMessage, block)
    }

    override suspend fun updateWorktrees(block: suspend () -> Either<WorktreeList, AppError>) {
        handleDataState(worktrees, block)
    }

    private suspend inline fun <T> handleDataState(
        flow: MutableStateFlow<DataState<T>>,
        block: suspend () -> Either<T, AppError>
    ) {
        flow.value = DataState.Loading
        val result = block()

        flow.value = when (result) {
            is Either.Err -> {
                printError(TAG, "Failed to load data: $result", (result.error as? GenericError)?.exception)
                DataState.Error(result.error)
            }
            is Either.Ok -> DataState.Loaded(result.value)
        }
    }
}
