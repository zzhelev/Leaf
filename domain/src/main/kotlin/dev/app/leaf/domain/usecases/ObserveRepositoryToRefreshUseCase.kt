package dev.app.leaf.domain.usecases

import dev.app.leaf.common.TabScope
import dev.app.leaf.common.printDebug
import dev.app.leaf.common.printError
import dev.app.leaf.common.systemSeparator
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.interfaces.IGetCommonGitDirGitAction
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.models.FileType
import dev.app.leaf.domain.models.WatcherEvent
import dev.app.leaf.domain.refresh.WatchedRepository
import dev.app.leaf.domain.refresh.WorkingTreeWatches
import dev.app.leaf.domain.refresh.WorktreeChangesRefresher
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ObserveRepositoryToRefreshUseCase"

private const val REFRESH_TIME_SINCE_LAST_OPERATION = 1_500L

/** The file watcher refreshes the worktree list at most this often while other worktrees keep changing. */
private val MIN_TIME_BETWEEN_WORKTREES_REFRESHES = 2.seconds

/**
 * Tab-scoped (fork-only), so that a switch to another worktree stops the watch that this tab's [invoke] started.
 */
@TabScope
class ObserveRepositoryToRefreshUseCase @Inject constructor(
    private val tabCoroutineScope: TabCoroutineScope,
    private val fileChangesWatcherProvider: Provider<IFileChangesWatcher>,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val refreshDataUseCase: RefreshDataUseCase,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val getStatusGitAction: IGetStatusGitAction,
    private val getCommonGitDirGitAction: IGetCommonGitDirGitAction,
) {
    @Volatile
    private var watch: Job? = null

    /**
     * Sometimes external apps can run filesystem multiple operations in a fraction of a second.
     * To prevent excessive updates, we add a slight delay between updates emission to prevent slowing down
     * the app by constantly running "git status" or even full refreshes.
     *
     */
    operator fun invoke() {
        // TODO add some logging?
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return
        // Fork-only: each watch has its own watcher, which it closes when it ends
        val fileChangesWatcher = fileChangesWatcherProvider.get()

        stop()

        watch = tabCoroutineScope.launch {
            val worktreeDir = getWorktreeUseCase().okOrNull() ?: return@launch
            // Fork-only: the git dir that the worktrees share, to see what changes in the other worktrees
            val commonDir = getCommonGitDirGitAction(repositoryPath).okOrNull() ?: repositoryPath
            val watchedRepository = WatchedRepository(File(repositoryPath), File(commonDir))
            val workingTreeWatches = WorkingTreeWatches(
                fileChangesWatcher,
                File(worktreeDir),
                isLeftOut = watchedRepository::isLinkedWorktreeFolder,
            )
            val worktreeChangesRefresher = WorktreeChangesRefresher(this, MIN_TIME_BETWEEN_WORKTREES_REFRESHES) {
                refreshDataUseCase(DataToRefresh.WORKTREES).join()
            }

            launch {
                fileChangesWatcher
                    .observeEvents()
                    .collect { event ->
                        when (event) {
                            is WatcherEvent.ChangesDetected -> {
                                val changes = event.changes

                                // git creates it with the first linked worktree, so it may not have been watched
                                val worktreesDir = watchedRepository.worktreesDir

                                val worktreesDirCreated = changes.any { watchedRepository.isWorktreesDir(it.path) }

                                if (worktreesDirCreated && worktreesDir.isDirectory) {
                                    fileChangesWatcher.addPathToWatch(worktreesDir.path, true)
                                }

                                // Fork-only: in every batch, also while an operation runs and just after, when nothing
                                // is refreshed here. Otherwise the folders that a checkout or a pull creates go unwatched
                                updateWatchedDirectories(
                                    event,
                                    repositoryPath,
                                    worktreeDir + systemSeparator,
                                    workingTreeWatches,
                                    watchedRepository,
                                )

                                // Nothing for Leaf's own files, JGit's probe files and commit messages being edited
                                val dataToRefresh = watchedRepository.dataToRefresh(changes.map { it.path })

                                if (dataToRefresh.isEmpty()) {
                                    return@collect
                                }

                                printDebug(TAG, "Changes detected: ${changes.toList()}, to refresh: $dataToRefresh")

                                if (canRefreshData()) {
                                    if (dataToRefresh == listOf(DataToRefresh.WORKTREES)) {
                                        worktreeChangesRefresher.onChanged()
                                    } else {
                                        refreshDataUseCase(*dataToRefresh.toTypedArray())
                                    }
                                } else {
                                    printDebug(TAG, "Ignoring detected changes because the time diff since last change is too short or currently running other tasks")
                                }
                            }

                            // Fork-only: what changed isn't known, and new folders may have gone unreported
                            is WatcherEvent.ChangesLost -> {
                                // Also while an operation runs, as a large checkout is what loses changes
                                if (watchedRepository.worktreesDir.isDirectory) {
                                    fileChangesWatcher.addPathToWatch(watchedRepository.worktreesDir.path, true)
                                }

                                watchWorkingTree(workingTreeWatches, repositoryPath)

                                if (canRefreshData()) {
                                    printDebug(TAG, "Changes went unreported, refreshing everything")
                                    refreshDataUseCase(DataToRefresh.ALL)
                                } else {
                                    printDebug(TAG, "Ignoring unreported changes because an operation runs or just ran")
                                }
                            }

                            is WatcherEvent.WatchInitError -> {
                                printDebug(TAG, "Watch init error: ${event.message}")
                            }
                        }
                    }
            }

            // Fork-only: a folder that gets ignored, or stops being, is watched as it now should be
            launch {
                repositoryDataRepository.status.collect { status ->
                    if (status is DataState.Loaded) {
                        workingTreeWatches.onStatus(status.data.ignored)
                    }
                }
            }

            fileChangesWatcher.addPathToWatch(worktreeDir, false)
            fileChangesWatcher.addPathToWatch(repositoryPath, false)
            fileChangesWatcher.addPathToWatch("$repositoryPath${systemSeparator}refs", true)
            fileChangesWatcher.addPathToWatch("$repositoryPath${systemSeparator}modules", true)
            // Fork-only: the other worktrees, and for a linked worktree the refs and files it shares with them
            fileChangesWatcher.addPathToWatch(watchedRepository.worktreesDir.path, true)

            if (watchedRepository.isLinkedWorktree) {
                fileChangesWatcher.addPathToWatch(commonDir, false)
                fileChangesWatcher.addPathToWatch("$commonDir${systemSeparator}refs", true)
            }

            watchWorkingTree(workingTreeWatches, repositoryPath)
        }.apply {
            invokeOnCompletion {
                fileChangesWatcher.close()
            }
        }
    }

    /**
     * Stops watching the tab's worktree (fork-only), before the tab switches to another one. The watch notices within
     * half a second, then its watcher closes.
     */
    fun stop() {
        watch?.cancel()
        watch = null
    }

    /**
     * Watches the working tree's folders that git doesn't ignore. Watching a folder again changes nothing. Whatever
     * has no ignored folder under it is watched with one registration (see [WorkingTreeWatches]).
     */
    private suspend fun watchWorkingTree(workingTreeWatches: WorkingTreeWatches, repositoryPath: String) {
        val status = getStatusGitAction(repositoryPath).okOrNull()

        if (status == null) {
            // Without the ignored folders, watching them all would watch every build output
            printError(TAG, "Not watching the working tree's folders, as its status couldn't be read")
            return
        }

        workingTreeWatches.watchAll(status.ignored)
    }

    private suspend fun canRefreshData(): Boolean {
        val canRefresh = if (repositoryStateRepository.currentTask.value != null) {
            false
        } else {
            val timeDiffInMs =
                System.currentTimeMillis() - repositoryStateRepository.lastOperationTimestamp.first()

            printDebug(TAG, "time diff in ms: $timeDiffInMs")

            timeDiffInMs > REFRESH_TIME_SINCE_LAST_OPERATION
        }

        printDebug(TAG, "canRefresh: $canRefresh")

        return canRefresh
    }

    private suspend fun updateWatchedDirectories(
        event: WatcherEvent.ChangesDetected,
        repositoryPath: String,
        worktreeDirPath: String,
        workingTreeWatches: WorkingTreeWatches,
        watchedRepository: WatchedRepository,
    ) {
        val directories = event
            .changes
            .filter { it.fileType == FileType.DIRECTORY }

        if (directories.isNotEmpty()) {
            val groupedDirs = directories
                .groupBy { File(it.path).exists() }

            val newDirs = groupedDirs[true].orEmpty()
            val removedDirs = groupedDirs[false].orEmpty()

            if (newDirs.isNotEmpty()) {
                val status = getStatusGitAction(
                    repositoryPath,
                    newDirs.map { it.path.removePrefix(worktreeDirPath) },
                ).okOrNull()

                for (dir in newDirs) {
                    if (
                        status != null &&
                        !status.ignored.contains(dir.path.removePrefix(worktreeDirPath)) &&
                        !watchedRepository.isLinkedWorktreeFolder(File(dir.path))
                    ) {
                        // The folders created inside it too, which no event reports once it has files
                        workingTreeWatches.watchNew(File(dir.path), status.ignored)
                    }
                }
            }

            // Fork-only: not only in a batch that also created a folder
            for (dir in removedDirs) {
                workingTreeWatches.unwatch(dir.path)
            }
        }
    }
}
