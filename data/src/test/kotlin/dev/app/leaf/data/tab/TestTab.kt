// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.GetWorktreePathGitAction
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.author.LoadAuthorGitAction
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.GetRefDatesGitAction
import dev.app.leaf.data.git.branches.GetRemoteBranchesGitAction
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.log.JGitGraphRevWalker
import dev.app.leaf.data.git.rebase.GetCommitFromRebaseLineGitAction
import dev.app.leaf.data.git.rebase.GetRebaseInteractiveTodoLinesGitAction
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.repository.OpenRepositoryGitAction
import dev.app.leaf.data.git.stash.GetStashListGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.tags.GetTagsGitAction
import dev.app.leaf.data.git.workspace.GetPersistedCommitMessagesGitAction
import dev.app.leaf.data.git.workspace.GetStatusGitAction
import dev.app.leaf.data.git.worktrees.GetAheadBehindGitAction
import dev.app.leaf.data.git.worktrees.GetCommitTimesGitAction
import dev.app.leaf.data.git.worktrees.GetCommonGitDirGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeBaseBranchGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeStatusGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreesGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.JGitRebaseTodoLineMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.JGitTagMapper
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.data.repositories.InMemoryRepositoryDataRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.AppStateManager
import dev.app.leaf.domain.DataRefreshRunner
import dev.app.leaf.domain.GraphLogGenerator
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.WorktreesRefreshRunner
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.WatcherEvent
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.usecases.GetLogUseCase
import dev.app.leaf.domain.usecases.GetRebaseInteractiveTodoLinesUseCase
import dev.app.leaf.domain.usecases.GetRebaseLinesFullMessageUseCase
import dev.app.leaf.domain.usecases.GetRemotesUseCase
import dev.app.leaf.domain.usecases.GetWorktreeUseCase
import dev.app.leaf.domain.usecases.GetWorktreesInfoUseCase
import dev.app.leaf.domain.usecases.ObserveRepositoryToRefreshUseCase
import dev.app.leaf.domain.usecases.OpenRepositoryUseCase
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A tab's repositories and use cases, built as Dagger builds them for each tab, with the real git actions. Each watch
 * of the file watcher gets a [RecordingFileChangesWatcher], in [watchers].
 */
class TestTab(jgit: JGit, gitCli: GitCli, statusDelay: Duration = Duration.ZERO) : AutoCloseable {
    val scope = TabCoroutineScope()
    val repositoryData = InMemoryRepositoryDataRepository()
    val repositoryState = InMemoryRepositoryStateRepository()
    val watchers: MutableList<RecordingFileChangesWatcher> = CopyOnWriteArrayList()

    private lateinit var refresh: RefreshDataUseCase

    val useCaseExecutor = UseCaseExecutor(repositoryData, repositoryState, Provider { refresh }, scope)

    private val commitMapper = JGitCommitMapper(JGitIdentityMapper())
    private val getBranches = GetBranchesGitAction(JGitBranchMapper(), jgit)
    private val getCurrentBranch = GetCurrentBranchGitAction(getBranches, jgit)
    private val getStashList = GetStashListGitAction(commitMapper, jgit)
    private val getStatus = GetStatusGitAction(jgit)
    private val getTags = GetTagsGitAction(JGitTagMapper(), jgit)
    private val getRemotes = GetRemotesUseCase(
        GetRemotesGitAction(RemoteConfigToRemoteMapper(), jgit),
        GetRemoteBranchesGitAction(JGitBranchMapper(), jgit),
        useCaseExecutor,
    )
    private val getWorktree = GetWorktreeUseCase(GetWorktreePathGitAction(jgit), useCaseExecutor)

    val refreshData = RefreshDataUseCase(
        useCaseExecutor = useCaseExecutor,
        getBranchesGitAction = getBranches,
        getCurrentBranchGitAction = getCurrentBranch,
        repositoryDataRepository = repositoryData,
        repositoryStateRepository = repositoryState,
        getStashListGitAction = getStashList,
        loadAuthorGitAction = LoadAuthorGitAction(jgit),
        getStatusGitAction = SlowStatus(getStatus, statusDelay),
        getRemotesUseCase = getRemotes,
        getSubmodulesGitAction = GetSubmodulesGitAction(jgit, JGitSubmoduleMapper()),
        getTagsGitAction = getTags,
        getRefDatesGitAction = GetRefDatesGitAction(jgit),
        getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
        getRebaseInteractiveTodoLinesUseCase = GetRebaseInteractiveTodoLinesUseCase(
            GetRebaseInteractiveTodoLinesGitAction(jgit, JGitRebaseTodoLineMapper()),
            useCaseExecutor,
        ),
        getRebaseLinesFullMessageUseCase = GetRebaseLinesFullMessageUseCase(
            GetCommitFromRebaseLineGitAction(commitMapper, jgit),
            useCaseExecutor,
        ),
        getPersistedCommitMessagesGitAction = GetPersistedCommitMessagesGitAction(jgit),
        getLogUseCase = GetLogUseCase(
            getBranchesGitAction = getBranches,
            getTagsGitAction = getTags,
            getRemotesUseCase = getRemotes,
            getStashListGitAction = getStashList,
            getStatusGitAction = getStatus,
            getCurrentBranchAction = getCurrentBranch,
            graphLogGenerator = GraphLogGenerator(Provider { JGitGraphRevWalker(jgit, commitMapper) }),
            repositoryDataRepository = repositoryData,
        ),
        getWorktreesInfoUseCase = GetWorktreesInfoUseCase(
            GetWorktreesGitAction(gitCli, jgit),
            GetWorktreeStatusGitAction(gitCli),
            GetAheadBehindGitAction(gitCli),
            GetWorktreeBaseBranchGitAction(jgit),
            GetCommitTimesGitAction(jgit),
            useCaseExecutor,
        ),
        worktreesRefreshRunner = WorktreesRefreshRunner(),
        dataRefreshRunner = DataRefreshRunner(),
        scope = scope,
    ).also { refresh = it }

    val observeRepositoryToRefresh = ObserveRepositoryToRefreshUseCase(
        tabCoroutineScope = scope,
        fileChangesWatcherProvider = Provider { RecordingFileChangesWatcher().also { watchers += it } },
        repositoryDataRepository = repositoryData,
        getWorktreeUseCase = getWorktree,
        refreshDataUseCase = refreshData,
        repositoryStateRepository = repositoryState,
        getStatusGitAction = getStatus,
        getCommonGitDirGitAction = GetCommonGitDirGitAction(jgit),
    )

    val openRepository = OpenRepositoryUseCase(
        repositoryDataRepository = repositoryData,
        repositoryStateRepository = repositoryState,
        openRepositoryGitAction = OpenRepositoryGitAction(),
        refreshDataUseCase = refreshData,
        observeRepositoryToRefreshUseCase = observeRepositoryToRefresh,
        getWorktreeUseCase = getWorktree,
        appStateManager = AppStateManager(mockk<AppSettingsRepository>(relaxed = true)),
    )

    /** Waits until [flow] has loaded data that passes [predicate]. */
    suspend fun <T> awaitLoaded(flow: Flow<DataState<T>>, predicate: (T) -> Boolean = { true }): T {
        return withTimeout(10.seconds) {
            (flow.first { it is DataState.Loaded && predicate(it.data) } as DataState.Loaded).data
        }
    }

    /** Waits until the file watcher has had [count] watches, and returns the last one. */
    suspend fun awaitWatch(count: Int): RecordingFileChangesWatcher = withTimeout(10.seconds) {
        while (watchers.size < count) {
            delay(10)
        }

        watchers[count - 1]
    }

    /** Cancels the tab and waits for what it runs, which could still write to the repository (JGit's probe files). */
    override fun close() = runBlocking {
        withTimeout(10.seconds) { scope.coroutineContext.job.cancelAndJoin() }
    }
}

/** Takes [delay] longer for each status, as in a large worktree, so that a test can see what happens meanwhile. */
private class SlowStatus(private val status: IGetStatusGitAction, private val delay: Duration) : IGetStatusGitAction {
    override suspend fun invoke(repository: String, paths: List<String>): Either<Status, AppError> {
        delay(delay)
        return status(repository, paths)
    }
}

/** A file watcher that only notes the folders it was given, and whether it was closed. It never sees changes. */
class RecordingFileChangesWatcher : IFileChangesWatcher {
    val watchedPaths: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var isClosed = false
        private set

    override fun addPathToWatch(path: String, isRecursive: Boolean) {
        watchedPaths += path
    }

    override fun removePathFromWatch(path: String) {
        watchedPaths -= path
    }

    override suspend fun observeEvents(): Flow<WatcherEvent> = flow { awaitCancellation() }

    override fun close() {
        isClosed = true
    }
}
