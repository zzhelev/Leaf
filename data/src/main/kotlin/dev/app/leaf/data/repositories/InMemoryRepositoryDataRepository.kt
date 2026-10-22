package dev.app.leaf.data.repositories

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.cherrypick.BranchesAhead
import dev.app.leaf.domain.cherrypick.CherryPickSequence
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    override val branchesAhead: Flow<DataState<BranchesAhead>>
        field = MutableStateFlow<DataState<BranchesAhead>>(DataState.Loading)

    override val cherryPickSequence: StateFlow<DataState<CherryPickSequence?>>
        field = MutableStateFlow<DataState<CherryPickSequence?>>(DataState.Loading)

    override var maxCommitsToLoadLimit: Int = 0

    override val repositoryPath: String?
        get() {
            return when (val state = repositorySelectionState.value) {
                is RepositorySelectionState.Open -> state.path
                else -> null
            }
        }

    /** Makes a switch to another worktree and a refresh's check of the git dir and write happen one at a time. */
    private val worktreeLock = Any()

    override fun setRepositorySelectionState(state: RepositorySelectionState) = synchronized(worktreeLock) {
        repositorySelectionState.value = state
    }

    override fun switchWorktree(gitDir: String) = synchronized(worktreeLock) {
        status.value = DataState.Loading
        currentBranch.value = DataState.Loading
        repositoryState.value = DataState.Loading
        rebaseInteractiveState.value = DataState.Loading
        persistedCommitMessage.value = DataState.Loading
        submodules.value = DataState.Loading
        // Fork-only: both follow the worktree's HEAD and git dir
        branchesAhead.value = DataState.Loading
        cherryPickSequence.value = DataState.Loading
        repositorySelectionState.value = RepositorySelectionState.Open(gitDir)
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
        branchesAhead.value = DataState.Loading
        cherryPickSequence.value = DataState.Loading
    }

    override suspend fun updateStatus(repositoryPath: String, block: suspend () -> Either<Status, AppError>) {
        handleDataState(status, repositoryPath, block)
    }

    override suspend fun updateLocalBranches(
        repositoryPath: String,
        block: suspend () -> Either<List<Branch>, AppError>,
    ) {
        handleDataState(localBranches, repositoryPath, block)
    }

    override suspend fun updateCurrentBranch(repositoryPath: String, block: suspend () -> Either<Branch?, AppError>) {
        handleDataState(currentBranch, repositoryPath, block)
    }

    override suspend fun updateTags(repositoryPath: String, block: suspend () -> Either<List<Tag>, AppError>) {
        handleDataState(tags, repositoryPath, block)
    }

    override suspend fun updateLog(repositoryPath: String, block: suspend () -> Either<GraphCommits, AppError>) {
        handleDataState(log, repositoryPath, block)
    }

    override suspend fun updateRemotes(
        repositoryPath: String,
        block: suspend () -> Either<List<RemoteInfo>, AppError>,
    ) {
        handleDataState(remotes, repositoryPath, block)
    }

    override suspend fun updateBranchesAhead(
        repositoryPath: String,
        block: suspend () -> Either<BranchesAhead, AppError>,
    ) {
        handleDataState(branchesAhead, repositoryPath, block)
    }

    override suspend fun updateCherryPickSequence(
        repositoryPath: String,
        block: suspend () -> Either<CherryPickSequence?, AppError>,
    ) {
        handleDataState(cherryPickSequence, repositoryPath, block)
    }

    override suspend fun updateRefDates(repositoryPath: String, block: suspend () -> Either<RefDates, AppError>) {
        handleDataState(refDates, repositoryPath, block)
    }

    override suspend fun updateStashes(repositoryPath: String, block: suspend () -> Either<List<Commit>, AppError>) {
        handleDataState(stashes, repositoryPath, block)
    }

    override suspend fun updateSubmodules(
        repositoryPath: String,
        block: suspend () -> Either<Map<String, Submodule>, AppError>,
    ) {
        handleDataState(submodules, repositoryPath, block)
    }

    override suspend fun updateAuthor(repositoryPath: String, block: suspend () -> Either<AuthorInfo, AppError>) {
        handleDataState(author, repositoryPath, block)
    }

    override suspend fun updateRepositoryState(
        repositoryPath: String,
        block: suspend () -> Either<RepositoryState, AppError>,
    ) {
        handleDataState(repositoryState, repositoryPath, block)
    }

    override suspend fun updateRebaseInteractiveState(
        repositoryPath: String,
        block: suspend () -> Either<RebaseInteractiveState, AppError>,
    ) {
        handleDataState(rebaseInteractiveState, repositoryPath, block)
    }

    override suspend fun updatePersistedCommitMessages(
        repositoryPath: String,
        block: suspend () -> Either<PersistedCommitMessage, AppError>,
    ) {
        handleDataState(persistedCommitMessage, repositoryPath, block)
    }

    override suspend fun updateWorktrees(repositoryPath: String, block: suspend () -> Either<WorktreeList, AppError>) {
        handleDataState(worktrees, repositoryPath, block)
    }

    private suspend inline fun <T> handleDataState(
        flow: MutableStateFlow<DataState<T>>,
        repositoryPath: String,
        block: suspend () -> Either<T, AppError>
    ) {
        // Fork-only: nothing for a worktree that the tab left before this refresh started, or while it ran
        if (!writeIfStillShown(repositoryPath) { flow.value = DataState.Loading }) {
            return
        }

        // Fork-only: a step that throws, such as the log's walk when an object goes missing, left the data Loading
        val result = try {
            block()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Either.Err(GenericError(e.message.orEmpty(), e))
        }

        writeIfStillShown(repositoryPath) {
            flow.value = when (result) {
                is Either.Err -> {
                    printError(TAG, "Failed to load data: $result", (result.error as? GenericError)?.exception)
                    DataState.Error(result.error)
                }
                is Either.Ok -> DataState.Loaded(result.value)
            }
        }
    }

    /**
     * Runs [write] if the tab still shows the worktree whose git dir is [repositoryPath], and tells whether it did. A
     * switch to another worktree can't happen in between (fork-only).
     */
    private inline fun writeIfStillShown(repositoryPath: String, write: () -> Unit): Boolean =
        synchronized(worktreeLock) {
            val isShown = repositoryPath == this.repositoryPath

            if (isShown) {
                write()
            }

            isShown
        }
}
