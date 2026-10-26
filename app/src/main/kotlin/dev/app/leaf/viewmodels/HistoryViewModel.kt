package dev.app.leaf.viewmodels

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.diff.rowKeptAcross
import dev.app.leaf.extensions.stateIn
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.isMissingObject
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.exceptions.MissingDiffEntryException
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.extensions.renamedFrom
import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.interfaces.IGenerateSplitHunkFromDiffResultGitAction
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.FailureSeverity
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.restore.FileRestorePlan
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.GetDiffUseCase
import dev.app.leaf.domain.usecases.GetFileCommitsUseCase
import dev.app.leaf.domain.usecases.GetFileDiffBetweenCommitsUseCase
import dev.app.leaf.domain.usecases.GetFileRestorePlanUseCase
import dev.app.leaf.domain.usecases.GetWorktreeUseCase
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import dev.app.leaf.domain.usecases.RestoreFileVersionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class HistoryViewModel @Inject constructor(
    private val getCommitDiffEntriesUseCase: GetCommitDiffEntriesUseCase,
    private val generateSplitHunkFromDiffResultGitAction: IGenerateSplitHunkFromDiffResultGitAction,
    private val settings: AppSettingsService,
    private val getFileCommitsUseCase: GetFileCommitsUseCase,
    private val getDiffUseCase: GetDiffUseCase,
    // Fork-only: "Restore this version of the file"
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val getFileRestorePlanUseCase: GetFileRestorePlanUseCase,
    private val restoreFileVersionUseCase: RestoreFileVersionUseCase,
    private val repositoryStateRepository: RepositoryStateRepository,
    // Fork-only: two versions of the file, compared
    private val getFileDiffBetweenCommitsUseCase: GetFileDiffBetweenCommitsUseCase,
    // Fork-only: the diff's syntax colours
    highlightTextUseCase: HighlightTextUseCase,
) : TabViewModel() {
    private val _historyState = MutableStateFlow<HistoryState>(HistoryState.Loading(""))
    val historyState: StateFlow<HistoryState> = _historyState

    private val _viewDiffResult = MutableStateFlow<ViewDiffResult>(ViewDiffResult.None)
    val viewDiffResult: StateFlow<ViewDiffResult> = _viewDiffResult

    // Fork-only: the colours of the diff's versions, which each diff is shown to before it's set
    private val diffHighlightsLoader = DiffHighlightsLoader(viewModelScope, highlightTextUseCase)
    val diffHighlights: StateFlow<HighlightedVersions> = diffHighlightsLoader.versions

    // Fork-only: the commit whose diff is shown, which the list highlights and the strip above the diff names
    private val _selectedCommit = MutableStateFlow<Commit?>(null)
    val selectedCommit: StateFlow<Commit?> = _selectedCommit

    private var selectCommitJob: Job? = null

    // Fork-only: two versions of the file that ⌘-click compares (docs/fork/compare.md, 7.3), in place of one commit's
    // change. The selected commit stays what it was, which ending the comparison selects again
    private val _versions = MutableStateFlow<FileVersions?>(null)
    val versions: StateFlow<FileVersions?> = _versions

    // Fork-only: whether the repository has a working tree to restore the file into. A bare one hasn't, and the rows'
    // menu then has no item for it.
    private val _canRestore = MutableStateFlow(false)
    val canRestore: StateFlow<Boolean> = _canRestore

    // Fork-only: the version that was last restored, which the view says until the user dismisses it
    private val _restored = MutableStateFlow<RestoredVersion?>(null)
    val restored: StateFlow<RestoredVersion?> = _restored

    var filePath: String = ""

    // Fork-only: made through newLazyListState, as selectCommit replaces it off the UI thread
    val lazyListState = MutableStateFlow(newLazyListState())


    init {
        // Fork-only: in the view model's scope, not the tab's, so that each file history opened stops following the
        // setting once it's closed
        viewModelScope.launch {
            settings.diffTextViewType.collect { diffType ->
                if (filePath.isNotBlank()) {
                    updateDiffType(diffType)
                }
            }
        }

        // Fork-only: ignoring whitespace changes the diff itself, so the shown commit's diff is made again, where it was
        viewModelScope.launch {
            settings.diffWhitespace.distinctUntilChanged().collect { mode ->
                val commit = selectedCommit.value
                val versions = versions.value

                if (filePath.isBlank() || mode == shownWhitespace) {
                    // Nothing to make again
                } else if (versions != null) {
                    compareVersions(versions, keepScroll = true)
                } else if (commit != null) {
                    selectCommit(commit, keepScroll = true)
                }
            }
        }
    }

    /** The whitespace mode of the diff that was last loaded, which a change of the setting is compared with. */
    private var shownWhitespace: DiffWhitespaceMode? = null

    /** What the View menu in the commit strip shows and sets (fork-only), as the diff pane's does. */
    val diffWhitespace: StateFlow<DiffWhitespaceMode> = settings.diffWhitespace.stateIn(DiffWhitespaceMode.DEFAULT)
    val diffShowWhitespace: StateFlow<Boolean> = settings.diffShowWhitespace.stateIn(false)

    fun changeDiffWhitespace(mode: DiffWhitespaceMode) {
        viewModelScope.launch { settings.setConfiguration(AppConfig.DiffWhitespace(mode)) }
    }

    fun changeDiffShowWhitespace(show: Boolean) {
        viewModelScope.launch { settings.setConfiguration(AppConfig.DiffShowWhitespace(show)) }
    }

    private fun updateDiffType(newDiffType: DiffTextViewType) {
        val viewDiffResult = this.viewDiffResult.value

        if (viewDiffResult is ViewDiffResult.Loaded) {
            val diffResult = viewDiffResult.diffResult

            if (diffResult is DiffResult.Text && newDiffType == DiffTextViewType.Split) { // Current is unified and new is split
                val hunksList = generateSplitHunkFromDiffResultGitAction(diffResult)
                _viewDiffResult.value = ViewDiffResult.Loaded(
                    diffType = viewDiffResult.diffType,
                    diffResult = DiffResult.TextSplit(diffResult.diffEntry, hunksList, diffResult.onlyWhitespaceChanged)
                )
            } else if (diffResult is DiffResult.TextSplit && newDiffType == DiffTextViewType.Unified) { // Current is split and new is unified
                val hunksList = diffResult.hunks.map { it.sourceHunk }

                _viewDiffResult.value = ViewDiffResult.Loaded(
                    diffType = viewDiffResult.diffType,
                    diffResult = DiffResult.Text(diffResult.diffEntry, hunksList, diffResult.onlyWhitespaceChanged)
                )
            }
        }
    }

    fun fileHistory(filePath: String) = viewModelScope.launch {
        this@HistoryViewModel.filePath = filePath
        _historyState.value = HistoryState.Loading(filePath)
        launch { _canRestore.value = getWorktreeUseCase() is Either.Ok }

        val log = getFileCommitsUseCase(filePath)

        _historyState.value = when (log) {
            is Either.Err -> HistoryState.Failed(filePath, log.error)

            is Either.Ok -> HistoryState.Loaded(filePath, log.value)
        }

        // Fork-only: opens on the newest commit, rather than on an empty diff
        (log as? Either.Ok)?.value?.firstOrNull()?.let { selectCommit(it) }
    }

    /**
     * Fork-only: looks at what restoring the file from [commit] would replace, for the dialog that asks first. A
     * failure opens the error dialog, as the restore's own would, and gives null.
     */
    suspend fun planRestore(commit: Commit): FileRestorePlan? {
        return when (val result = getFileRestorePlanUseCase(commit, filePath)) {
            is Either.Ok -> result.value

            is Either.Err -> {
                repositoryStateRepository.addCompletedTaskFailed(
                    TaskType.RestoreFileVersion,
                    result.error,
                    FailureSeverity.HIGH,
                )
                null
            }
        }
    }

    /**
     * Fork-only: writes the file as [commit] had it into the working tree. The task shows its own failure; once it
     * worked, [restored] says so, as the status pane that shows the file's change isn't on screen here.
     */
    fun restore(commit: Commit) {
        val path = filePath
        _restored.value = null

        viewModelScope.launch {
            if (restoreFileVersionUseCase(commit, path).await() is Either.Ok) {
                _restored.value = RestoredVersion(path, commit)
            }
        }
    }

    fun dismissRestored() {
        _restored.value = null
    }

    /**
     * Fork-only: selects the commit [offset] rows below the selected one, newer when negative (↑ and ↓). While two
     * versions are compared, from A, as the log's keys go on from A.
     */
    fun selectAdjacentCommit(offset: Int) {
        val commits = (historyState.value as? HistoryState.Loaded)?.commits ?: return

        adjacentCommit(commits, versions.value?.a ?: selectedCommit.value, offset)?.let { selectCommit(it) }
    }

    /**
     * Fork-only: ⌘-click on a row (Ctrl-click elsewhere), and the row menu's "Compare with selected": compares the file
     * at the row with the file at the selected commit, the older first. While comparing, it drops a side or replaces B.
     */
    fun pickCommit(commit: Commit) {
        val commits = (historyState.value as? HistoryState.Loaded)?.commits ?: return

        when (val pick = versionPick(commits, versions.value, selectedCommit.value, commit)) {
            is VersionPick.Select -> selectCommit(pick.commit)
            is VersionPick.Compare -> compareVersions(pick.versions)
        }
    }

    /** Fork-only: the file from B to A instead. */
    fun swapVersions() {
        val versions = versions.value ?: return

        compareVersions(versions.copy(a = versions.b, b = versions.a))
    }

    /** Fork-only: ends the comparison of two versions, back to the commit that was selected before it (Esc). */
    fun endVersions() {
        versions.value?.let { selectCommit(it.before) }
    }

    /** Fork-only: loads the shown diff again after it failed, the two versions' or the selected commit's. */
    fun reloadDiff() {
        val versions = versions.value
        val commit = selectedCommit.value

        if (versions != null) {
            compareVersions(versions)
        } else if (commit != null) {
            selectCommit(commit)
        }
    }

    /** Fork-only: shows the file from [versions]' A to its B. Both the same shows no diff. */
    private fun compareVersions(versions: FileVersions, keepScroll: Boolean = false) {
        _versions.value = versions

        val previousJob = selectCommitJob
        selectCommitJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()

            try {
                when (val result = getFileDiffBetweenCommitsUseCase(filePath, versions.a, versions.b)) {
                    is Either.Err -> {
                        ensureActive()
                        _viewDiffResult.value = ViewDiffResult.Failed(null, result.error)
                    }

                    is Either.Ok -> {
                        val diffEntry = result.value

                        if (diffEntry == null) {
                            ensureActive()
                            _viewDiffResult.value = ViewDiffResult.DiffNotFound(null)
                        } else {
                            showDiff(diffEntry, keepScroll)
                        }
                    }
                }
            } catch (ex: MissingDiffEntryException) {
                _viewDiffResult.value = ViewDiffResult.DiffNotFound(null)
            }
        }
    }

    /** [keepScroll]: the same commit's diff is made again (fork-only), so it stays where it was scrolled to. */
    fun selectCommit(commit: Commit, keepScroll: Boolean = false) {
        _selectedCommit.value = commit
        _versions.value = null

        // Fork-only: the arrow keys select commits faster than their diffs load, and the last one selected must be
        // the diff shown, so each load waits for the one before it to stop
        val previousJob = selectCommitJob
        selectCommitJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()

            try {
                val diffEntriesResult = getCommitDiffEntriesUseCase(commit)

                // Fork-only: a partial clone that couldn't download what the commit's diff reads says so
                if (diffEntriesResult is Either.Err && diffEntriesResult.error.isMissingObject()) {
                    _viewDiffResult.value = ViewDiffResult.Failed(null, diffEntriesResult.error)
                    return@launch
                }

                val diffEntries = diffEntriesResult.okOrNull().orEmpty()
                // Fork-only: the commit that moved the file is also in the history of the name that it had
                val diffEntry = diffEntries.firstOrNull { entry ->
                    entry.filePath == this@HistoryViewModel.filePath ||
                            entry.renamedFrom?.path == this@HistoryViewModel.filePath
                }

                if (diffEntry == null) {
                    _viewDiffResult.value = ViewDiffResult.DiffNotFound(null)
                    return@launch
                }

                showDiff(diffEntry, keepScroll)
            } catch (ex: Exception) {
                if (ex is CancellationException) {
                    throw ex
                } else if (ex is MissingDiffEntryException) {
                    //TODO Call refreshStatusUseCase ? tabState.refreshData(refreshType = RefreshType.UNCOMMITTED_CHANGES)
                    _viewDiffResult.value = ViewDiffResult.DiffNotFound(null)
                } else
                    ex.printStackTrace()
            }
        }
    }

    /** Shows [diffEntry]'s diff, the selected commit's or two versions' (fork-only, taken out of [selectCommit]). */
    private suspend fun CoroutineScope.showDiff(diffEntry: DiffEntry, keepScroll: Boolean) {
        val diffType = DiffType.CommitDiff(diffEntry)
        val whitespace = settings.diffWhitespace.first()
        val diffResult = getDiffUseCase(
            diffType,
            settings.diffTextViewType.first(),
            settings.diffDisplayFullFile.first(),
            whitespace,
        )
        // Fork-only: GetDiffUseCase turns a cancellation into DiffNotFound, which would blank the diff until the next
        // commit's loads. And another commit's diff starts at its top, as in the diff pane
        ensureActive()
        shownWhitespace = whitespace

        if (!keepScroll) {
            lazyListState.value = newLazyListState()
        } else {
            // The line at the top of the list stays at the top, though the rows change
            val before = (viewDiffResult.value as? ViewDiffResult.Loaded)?.diffResult
            val after = (diffResult as? ViewDiffResult.Loaded)?.diffResult

            rowKeptAcross(before, lazyListState.value.firstVisibleItemIndex, after)
                ?.let { row -> lazyListState.value = newLazyListState(row) }
        }

        diffHighlightsLoader.show(diffResult)
        _viewDiffResult.value = diffResult
    }
}

/** Fork-only: [filePath] was written into the working tree as [commit] had it. */
data class RestoredVersion(val filePath: String, val commit: Commit)

sealed class HistoryState(val filePath: String) {
    class Loading(filePath: String) : HistoryState(filePath)
    class Loaded(filePath: String, val commits: List<Commit>) : HistoryState(filePath)
    class Failed(filePath: String, val error: AppError) : HistoryState(filePath)
}

