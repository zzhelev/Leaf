package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RepositoryDataRepository {
    val status: Flow<DataState<Status>>
    val localBranches: Flow<DataState<List<Branch>>>
    val currentBranch: Flow<DataState<Branch?>>
    val tags: Flow<DataState<List<Tag>>>
    val remotes: Flow<DataState<List<RemoteInfo>>>
    val refDates: Flow<DataState<RefDates>>
    val log: StateFlow<DataState<GraphCommits>>
    val stashes: Flow<DataState<List<Commit>>>
    val repositorySelectionState: StateFlow<RepositorySelectionState>
    val repositoryState: StateFlow<DataState<RepositoryState>>
    val rebaseInteractiveState: StateFlow<DataState<RebaseInteractiveState>>
    val repositoryPath: String?
    val submodules: Flow<DataState<Map<String, Submodule>>>
    val author: Flow<DataState<AuthorInfo>>
    val persistedCommitMessage: Flow<DataState<PersistedCommitMessage>>
    val worktrees: Flow<DataState<WorktreeList>>
    var maxCommitsToLoadLimit: Int

    fun setRepositorySelectionState(state: RepositorySelectionState)
    fun clearAll()

    // Each update gets the git dir its block reads. A result for a git dir that the tab left while it loaded (it
    // switched to another worktree) is dropped (fork-only).
    suspend fun updateStatus(repositoryPath: String, block: suspend () -> Either<Status, AppError>)
    suspend fun updateLocalBranches(repositoryPath: String, block: suspend () -> Either<List<Branch>, AppError>)
    suspend fun updateCurrentBranch(repositoryPath: String, block: suspend () -> Either<Branch?, AppError>)
    suspend fun updateTags(repositoryPath: String, block: suspend () -> Either<List<Tag>, AppError>)
    suspend fun updateLog(repositoryPath: String, block: suspend () -> Either<GraphCommits, AppError>)
    suspend fun updateRemotes(repositoryPath: String, block: suspend () -> Either<List<RemoteInfo>, AppError>)
    suspend fun updateRefDates(repositoryPath: String, block: suspend () -> Either<RefDates, AppError>)
    suspend fun updateStashes(repositoryPath: String, block: suspend () -> Either<List<Commit>, AppError>)
    suspend fun updateSubmodules(repositoryPath: String, block: suspend () -> Either<Map<String, Submodule>, AppError>)
    suspend fun updateAuthor(repositoryPath: String, block: suspend () -> Either<AuthorInfo, AppError>)
    suspend fun updateRepositoryState(repositoryPath: String, block: suspend () -> Either<RepositoryState, AppError>)
    suspend fun updateRebaseInteractiveState(
        repositoryPath: String,
        block: suspend () -> Either<RebaseInteractiveState, AppError>,
    )
    suspend fun updatePersistedCommitMessages(
        repositoryPath: String,
        block: suspend () -> Either<PersistedCommitMessage, AppError>,
    )
    suspend fun updateWorktrees(repositoryPath: String, block: suspend () -> Either<WorktreeList, AppError>)
}

sealed interface DataState<out T> {
    data object Loading : DataState<Nothing>
    data class Loaded<T>(val data: T) : DataState<T>
    data class Error(val error: AppError) : DataState<Nothing>
}

fun <T> DataState<T>.dataOrNull(): T? = (this as? DataState.Loaded)?.data
