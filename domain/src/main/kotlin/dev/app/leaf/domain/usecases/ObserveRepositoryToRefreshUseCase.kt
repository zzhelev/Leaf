package dev.app.leaf.domain.usecases

import dev.app.leaf.FileType
import dev.app.leaf.common.printDebug
import dev.app.leaf.common.printError
import dev.app.leaf.common.systemSeparator
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.interfaces.IGetCommonGitDirGitAction
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.models.WatcherEvent
import dev.app.leaf.domain.refresh.WatchedRepository
import dev.app.leaf.domain.refresh.WorktreeChangesRefresher
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ObserveRepositoryToRefreshUseCase"

private const val REFRESH_TIME_SINCE_LAST_OPERATION = 1_500L

/** The file watcher refreshes the worktree list at most this often while other worktrees keep changing. */
private val MIN_TIME_BETWEEN_WORKTREES_REFRESHES = 2.seconds

class ObserveRepositoryToRefreshUseCase @Inject constructor(
    private val tabCoroutineScope: TabCoroutineScope,
    private val fileChangesWatcher: IFileChangesWatcher,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val refreshDataUseCase: RefreshDataUseCase,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val getStatusGitAction: IGetStatusGitAction,
    private val getCommonGitDirGitAction: IGetCommonGitDirGitAction,
) {
    /**
     * Sometimes external apps can run filesystem multiple operations in a fraction of a second.
     * To prevent excessive updates, we add a slight delay between updates emission to prevent slowing down
     * the app by constantly running "git status" or even full refreshes.
     *
     */
    operator fun invoke() {
        // TODO add some logging?
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return
        tabCoroutineScope.launch {
            val worktreeDir = getWorktreeUseCase().okOrNull() ?: return@launch
            // Fork-only: the git dir that the worktrees share, to see what changes in the other worktrees
            val commonDir = getCommonGitDirGitAction(repositoryPath).okOrNull() ?: repositoryPath
            val watchedRepository = WatchedRepository(File(repositoryPath), File(commonDir))
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

                                // Nothing for Leaf's own files, JGit's probe files and commit messages being edited
                                val dataToRefresh = watchedRepository.dataToRefresh(changes.map { it.path })

                                if (dataToRefresh.isEmpty()) {
                                    return@collect
                                }

                                printDebug(TAG, "Changes detected: ${changes.toList()}, to refresh: $dataToRefresh")

                                if (canRefreshData()) {
                                    updateWatchedDirectories(
                                        event,
                                        repositoryPath,
                                        worktreeDir + systemSeparator,
                                        watchedRepository,
                                    )

                                    if (dataToRefresh == listOf(DataToRefresh.WORKTREES)) {
                                        worktreeChangesRefresher.onChanged()
                                    } else {
                                        refreshDataUseCase(*dataToRefresh.toTypedArray())
                                    }
                                } else {
                                    printDebug(TAG, "Ignoring detected changes because the time diff since last change is too short or currently running other tasks")
                                }
                            }

                            is WatcherEvent.WatchInitError -> {
                                printDebug(TAG, "Watch init error: ${event.code}")
                            }
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

            val status = getStatusGitAction(repositoryPath).okOrNull()

            val worktreeDirFile = File(worktreeDir)

            val dirs = getDirsToWatch(
                worktreeDir = worktreeDir + systemSeparator,
                excludedRelativePaths = HashSet(listOf(".git")),
                dirFile = worktreeDirFile,
                ignoreList = status?.ignored.orEmpty(),
                watchedRepository = watchedRepository,
            )

            if (status != null) {
                for (child in dirs) {
                    if (!status.ignored.contains(child.absolutePath.removePrefix(repositoryPath))) {
                        fileChangesWatcher.addPathToWatch(child.absolutePath, false)
                    }
                }
            }
        }.invokeOnCompletion {
            fileChangesWatcher.close()
        }
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
                        fileChangesWatcher.addPathToWatch(dir.path, false)
                    }
                }

                for (dir in removedDirs) {
                    fileChangesWatcher.removePathFromWatch(dir.path)
                }
            }
        }
    }

    private fun getDirsToWatch(
        worktreeDir: String,
        excludedRelativePaths: HashSet<String>,
        dirFile: File,
        ignoreList: List<String>,
        watchedRepository: WatchedRepository,
    ): List<File> {
        val childrenDirs = try {
            dirFile.listFiles { file -> file.isDirectory }
        } catch (e: Exception) {
            printError(TAG, e.message.orEmpty(), e)
            emptyArray()
        }
            .filter {
                val relativePath = it.absolutePath.removePrefix(worktreeDir)
                !ignoreList.contains(relativePath) &&
                        !excludedRelativePaths.contains(relativePath) &&
                        // Fork-only: the worktree list follows the changes in agents' worktrees inside this one
                        !watchedRepository.isLinkedWorktreeFolder(it)
            }

        return childrenDirs + childrenDirs.flatMap {
            getDirsToWatch(
                worktreeDir,
                excludedRelativePaths,
                it,
                ignoreList,
                watchedRepository,
            )
        }
    }
}
