package dev.app.leaf.viewmodels

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.app.leaf.Screen
import dev.app.leaf.TabViewModel
import dev.app.leaf.common.printLog
import dev.app.leaf.di.TabComponent
import dev.app.leaf.domain.cherrypick.CherryPickPlan
import dev.app.leaf.domain.rebase.SkippedCommitsNotice
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.usecases.CherryPickCommitsUseCase
import dev.app.leaf.domain.usecases.DeleteRemoteBranchUseCase
import dev.app.leaf.domain.MAX_COMPLETED_TASKS_KEPT
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.credentials.CredentialsState
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IInitLocalRepositoryGitAction
import dev.app.leaf.domain.models.AutostashNotice
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.worktreeSwitches
import dev.app.leaf.domain.remote.StoppedWaitingCheck
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.FailureSeverity
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.usecases.OpenRepositoryUseCase
import dev.app.leaf.domain.usecases.SetRepositorySelectionStateToNoneUseCase
import dev.app.leaf.domain.usecases.SwitchWorktreeUseCase
import dev.app.leaf.domain.worktrees.tabNamesOf
import dev.app.leaf.domain.worktrees.workTreeOf
import dev.app.leaf.extensions.stateIn
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.domain.AppStateManager
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.system.OpenUrlInBrowserUseCase
import dev.app.leaf.system.PickerType
import dev.app.leaf.ui.AppViewModel
import dev.app.leaf.ui.IVerticalSplitPaneConfig
import dev.app.leaf.ui.VerticalSplitPaneConfig
import dev.app.leaf.ui.components.TabInformationProvider
import dev.app.leaf.updates.Update
import dev.app.leaf.updates.UpdatesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val MIN_TIME_AFTER_GIT_OPERATION = 2000L

private const val TAG = "RepositoryTabViewModel"

/**
 * Contains all the information related to a tab and its subcomponents (smaller composables like the log, branches,
 * commit changes, etc.). It holds a reference to every view model because this class lives as long as the tab is open (survives
 * across full app recompositions), therefore, tab's content can be recreated with these view models.
 */
class RepositoryTabViewModel @AssistedInject constructor(
    private val initLocalRepositoryGitAction: IInitLocalRepositoryGitAction,
    val appStateManager: AppStateManager,
    private val credentialsStateManager: CredentialsStateManager,
    private val openFilePickerUseCase: OpenFilePickerUseCase,
    private val openUrlInBrowserUseCase: OpenUrlInBrowserUseCase,
    private val tabScope: TabCoroutineScope,
    private val verticalSplitPaneConfig: VerticalSplitPaneConfig,
    private val openRepositoryUseCase: OpenRepositoryUseCase,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val setRepositorySelectionStateToNoneUseCase: SetRepositorySelectionStateToNoneUseCase,
    private val switchWorktreeUseCase: SwitchWorktreeUseCase,
    private val tabComponent: TabComponent,
    private val appViewModel: AppViewModel,
    private val cherryPickCommitsUseCase: CherryPickCommitsUseCase,
    private val deleteRemoteBranchUseCase: DeleteRemoteBranchUseCase,
    private val stoppedWaitingCheck: StoppedWaitingCheck,
    @Assisted val initialPath: String?,
    updatesRepository: UpdatesRepository,
) : IVerticalSplitPaneConfig by verticalSplitPaneConfig,
    TabViewModel(),
    TabInformationProvider {
    @AssistedFactory
    interface Factory {
        fun create(initialPath: String?): RepositoryTabViewModel
    }

    var isLoaded = false
        private set

    val savedStates = mutableMapOf<String, Pair<Any?, Any?>>()

    val viewModelsMap = mutableMapOf<NavKey, TabViewModel>()

    fun <T : TabViewModel> getViewModel(key: NavKey, provideVM: (TabComponent) -> T): T {
        if (!viewModelsMap.contains(key)) {
            viewModelsMap[key] = provideVM(tabComponent)
        }

        return viewModelsMap.getValue(key) as T
    }

    fun removeViewModel(key: NavKey) {
        if (!backStack.contains(key)) {
            printLog(TAG, "TAB ${tabName.value} - Removing view model for key $key")
            viewModelsMap[key]?.onClear()
            viewModelsMap.remove(key)
        } else {
            printLog(TAG, "TAB ${tabName.value} - Keeping view model for key $key")
        }
    }


    private val allErrors = repositoryStateRepository
        .completedTasks
        .map { it.filterIsInstance<CompletedTask.Failure>() }
        .distinctUntilChanged()

    private val alreadyDisplayedCompletedTasks = MutableStateFlow<List<CompletedTask>>(emptyList())

    val severeErrors = combine(
        allErrors,
        alreadyDisplayedCompletedTasks.filterIsInstance<List<CompletedTask.Failure>>()
    ) { allErrors, alreadyDisplayedErrors ->
        allErrors - alreadyDisplayedErrors
    }
        .map { it.filter { it.severity == FailureSeverity.HIGH } }
        .distinctUntilChanged()
        .stateIn(emptyList())

    val notifications = combine(
        repositoryStateRepository.completedTasks,
        alreadyDisplayedCompletedTasks,
    ) { tasks, alreadyDisplayedTasks ->
        tasks - alreadyDisplayedTasks
    }
        .map { tasks ->
            tasks.filter { task ->
                when (task) {
                    is CompletedTask.Failure if task.severity == FailureSeverity.HIGH -> false
                    else -> true
                }
            }
        }
        .stateIn(emptyList())

    val repositorySelectionState: StateFlow<RepositorySelectionState> =
        repositoryDataRepository.repositorySelectionState


    /** The tab's working tree, which its tooltip shows: a linked worktree's folder, not its git dir (fork-only). */
    val repositoryPath = repositoryDataRepository
        .repositorySelectionState
        .map { state ->
            ((state as? RepositorySelectionState.Open)?.path ?: initialPath)?.let { workTreeOf(it) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(null)

    /** The repository's name, and the linked worktree's that the tab shows (fork-only). */
    private val tabNames = repositoryDataRepository
        .repositorySelectionState
        .map { state ->
            ((state as? RepositorySelectionState.Open)?.path ?: initialPath)?.let { tabNamesOf(it) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(null)

    override val tabName: StateFlow<String?> = tabNames
        .map { it?.repository }
        .stateIn(null)

    override val worktreeName: StateFlow<String?> = tabNames
        .map { it?.worktree }
        .stateIn(null)

    override val extraInfo: StateFlow<String?> = repositoryPath

    val backStack = NavBackStack<Screen>(
        if (initialPath == null) {
            Screen.Welcome
        } else {
            Screen.RepositoryLoading
        }
    )

    val processingTask = repositoryStateRepository.currentTask
        .debounce(300L.milliseconds)
        .stateIn(initialValue = null)

    val taskProgress: StateFlow<TaskProgress?> = repositoryStateRepository.taskProgress

    /** Changes that git set aside and couldn't put back, shown one after another until dismissed (fork-only). */
    val autostashNotices: StateFlow<List<AutostashNotice>> = repositoryStateRepository.autostashNotices

    /** The commits that Skip left out of a rebase that has ended, shown one notice after another (fork-only). */
    val skippedCommitsNotices: StateFlow<List<SkippedCommitsNotice>> = repositoryStateRepository.skippedCommitsNotices

    val credentialsState: StateFlow<CredentialsState> = credentialsStateManager.credentialsState


    init {
        // Fork-only: the tab now has another git dir to save, and the one it left may be open in no other tab
        viewModelScope.launch {
            repositorySelectionState.worktreeSwitches().collect { appViewModel.worktreeSwitched() }
        }
    }

    /** Switches the tab to the worktree whose folder is [path] (fork-only). */
    fun switchToWorktree(path: String) {
        switchWorktreeUseCase(path)
    }

    fun openRepository(directory: String) {
        viewModelScope.launch {
            printLog(TAG, "Trying to open repository ${directory}")

            openRepositoryUseCase(directory)
        }
    }

    fun autostashNoticeShown(notice: AutostashNotice) {
        repositoryStateRepository.autostashNoticeShown(notice)
    }

    fun skippedCommitsNoticeShown(notice: SkippedCommitsNotice) {
        repositoryStateRepository.skippedCommitsNoticeShown(notice)
    }

    /**
     * Fork-only: cherry-picks the commits that Skip left out back onto the current branch, oldest first, as the rebase
     * met them. They stop at conflicts as any cherry-pick of several commits does.
     */
    fun cherryPickBack(notice: SkippedCommitsNotice) {
        repositoryStateRepository.skippedCommitsNoticeShown(notice)
        cherryPickCommitsUseCase(CherryPickPlan(workingTree = "", notice.commits.map { it.commit }, squashMessage = null))
    }

    /** Deletes a remote branch that someone pushed to, from the error dialog that said so, with its new commit as the lease. */
    fun deleteRemoteBranch(branch: Branch) {
        deleteRemoteBranchUseCase(branch)
    }

    fun completedTaskAlreadyShown(task: CompletedTask) {
        alreadyDisplayedCompletedTasks.update {
            it
                .toMutableList()
                .apply { add(task) }
                .takeLast(MAX_COMPLETED_TASKS_KEPT)
        }
    }

    fun credentialsDenied() {
        credentialsStateManager.credentialsDenied()
    }

    fun httpCredentialsAccepted(user: String, password: String) {
        credentialsStateManager.httpCredentialsAccepted(user, password)
    }

    fun sshCredentialsAccepted(password: String) {
        credentialsStateManager.sshCredentialsAccepted(password)
    }

    fun sshHostKeyTrusted() {
        credentialsStateManager.sshHostKeyTrusted()
    }

    fun lfsCredentialsAccepted(user: String, password: String) {
        credentialsStateManager.lfsCredentialsAccepted(user, password)
    }

    fun promptAnswered(answer: String) {
        credentialsStateManager.promptAnswered(answer)
    }

    fun confirmed() {
        credentialsStateManager.confirmed()
    }

    /** Fork-only: whether the tab's conflict resolver has changes that aren't saved, which closing the tab would lose. */
    val conflictResolverHasUnsavedChanges: Boolean
        get() = repositoryOpenViewModel()?.conflictResolverHasUnsavedChanges == true

    /** Fork-only: the tab's conflict resolver file, when it has changes that aren't saved, which quitting would lose. */
    val conflictResolverUnsavedFile: String?
        get() = repositoryOpenViewModel()?.conflictResolverUnsavedFile

    /** Fork-only: saves the tab's conflict resolver file before Leaf quits, then calls [onSaved], or [onFailed]. */
    fun saveConflictResolverBeforeQuit(onSaved: () -> Unit, onFailed: () -> Unit) {
        val repositoryOpen = repositoryOpenViewModel()

        if (repositoryOpen == null) onSaved() else repositoryOpen.saveConflictResolverBeforeQuit(onSaved, onFailed)
    }

    /** Fork-only: runs [then], which closes the tab, once its conflict resolver has no changes that aren't saved. */
    fun whenConflictResolverLeft(then: () -> Unit) {
        val repositoryOpen = repositoryOpenViewModel()

        if (repositoryOpen == null) then() else repositoryOpen.whenConflictResolverLeft(then)
    }

    /** Fork-only: the Actions list's "Compare…", from the tab's current branch (`docs/fork/compare.md`, 7.1). */
    fun compareFromCurrentBranch() {
        repositoryOpenViewModel()?.compareCurrentWithAny()
    }

    private fun repositoryOpenViewModel() = viewModelsMap.values.filterIsInstance<RepositoryOpenViewModel>().firstOrNull()

    override fun dispose() {
        // Fork-only: the view models' own scopes, which cancelling the tab's scope doesn't stop
        viewModelsMap.values.forEach { it.onClear() }
        viewModelsMap.clear()
        onClear()

        // Ends the file watcher's watch too, which closes it
        tabScope.cancel()
        repositoryDataRepository.clearAll()
    }

    fun openDirectoryPicker(): String? {
        val latestDirectoryOpened = appStateManager.latestOpenedRepositoryPath

        return openFilePickerUseCase(PickerType.DIRECTORIES, latestDirectoryOpened)
    }

    fun initLocalRepository(dir: String) = viewModelScope.launch {
        val repoDir = File(dir)

        // A failure, such as a folder that can't be written, is shown instead of being lost with the coroutine
        // (fork-only)
        when (val result = initLocalRepositoryGitAction(repoDir)) {
            is Either.Err -> repositoryStateRepository.addCompletedTaskFailed(
                TaskType.RepositoryInit,
                result.error,
                FailureSeverity.HIGH,
            )

            is Either.Ok -> openRepository(dir)
        }
    }

    val update: StateFlow<Update?> = updatesRepository.update

    /**
     * Stops the foreground task, which only the tasks that report progress (the git CLI's) can do. Fork-only: when git
     * had handed the server what it carries out on its own (Stop waiting), the server is then asked what it did.
     */
    fun cancelOngoingTask() {
        repositoryStateRepository.cancelCurrentTask()?.let { stoppedWaitingCheck.start(it) }
    }

    fun openUrlInBrowser(url: String) {
        openUrlInBrowserUseCase(url)
    }

    fun removeRepositoryFromRecent(repository: String) = viewModelScope.launch {
        appStateManager.removeRepositoryFromRecent(repository)
    }

    fun loadTab() {
        if (!isLoaded) {
            isLoaded = true
            if (initialPath != null) {
                openRepository(initialPath)
            } else {
                setRepositorySelectionStateToNoneUseCase()
            }
        }
    }
}
