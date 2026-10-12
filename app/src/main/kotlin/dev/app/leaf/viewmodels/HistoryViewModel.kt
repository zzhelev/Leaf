package dev.app.leaf.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.exceptions.MissingDiffEntryException
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.interfaces.IGenerateSplitHunkFromDiffResultGitAction
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.GetDiffUseCase
import dev.app.leaf.domain.usecases.GetFileCommitsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class HistoryViewModel @Inject constructor(
    private val getCommitDiffEntriesUseCase: GetCommitDiffEntriesUseCase,
    private val generateSplitHunkFromDiffResultGitAction: IGenerateSplitHunkFromDiffResultGitAction,
    private val settings: AppSettingsService,
    private val getFileCommitsUseCase: GetFileCommitsUseCase,
    private val getDiffUseCase: GetDiffUseCase,
) : TabViewModel() {
    private val _historyState = MutableStateFlow<HistoryState>(HistoryState.Loading(""))
    val historyState: StateFlow<HistoryState> = _historyState

    private val _viewDiffResult = MutableStateFlow<ViewDiffResult>(ViewDiffResult.None)
    val viewDiffResult: StateFlow<ViewDiffResult> = _viewDiffResult

    // Fork-only: the commit whose diff is shown, which the list highlights and the strip above the diff names
    private val _selectedCommit = MutableStateFlow<Commit?>(null)
    val selectedCommit: StateFlow<Commit?> = _selectedCommit

    private var selectCommitJob: Job? = null

    var filePath: String = ""

    val lazyListState = MutableStateFlow(
        LazyListState(
            0,
            0
        )
    )


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
    }

    private fun updateDiffType(newDiffType: DiffTextViewType) {
        val viewDiffResult = this.viewDiffResult.value

        if (viewDiffResult is ViewDiffResult.Loaded) {
            val diffResult = viewDiffResult.diffResult

            if (diffResult is DiffResult.Text && newDiffType == DiffTextViewType.Split) { // Current is unified and new is split
                val hunksList = generateSplitHunkFromDiffResultGitAction(diffResult)
                _viewDiffResult.value = ViewDiffResult.Loaded(
                    diffType = viewDiffResult.diffType,
                    diffResult = DiffResult.TextSplit(diffResult.diffEntry, hunksList)
                )
            } else if (diffResult is DiffResult.TextSplit && newDiffType == DiffTextViewType.Unified) { // Current is split and new is unified
                val hunksList = diffResult.hunks.map { it.sourceHunk }

                _viewDiffResult.value = ViewDiffResult.Loaded(
                    diffType = viewDiffResult.diffType,
                    diffResult = DiffResult.Text(diffResult.diffEntry, hunksList)
                )
            }
        }
    }

    fun fileHistory(filePath: String) = viewModelScope.launch {
        this@HistoryViewModel.filePath = filePath
        _historyState.value = HistoryState.Loading(filePath)

        val log = getFileCommitsUseCase(filePath)

        _historyState.value = when (log) {
            is Either.Err -> HistoryState.Failed(filePath, log.error)

            is Either.Ok -> HistoryState.Loaded(filePath, log.value)
        }

        // Fork-only: opens on the newest commit, rather than on an empty diff
        (log as? Either.Ok)?.value?.firstOrNull()?.let { selectCommit(it) }
    }

    /** Fork-only: selects the commit [offset] rows below the selected one, newer when negative (↑ and ↓). */
    fun selectAdjacentCommit(offset: Int) {
        val commits = (historyState.value as? HistoryState.Loaded)?.commits ?: return

        adjacentCommit(commits, selectedCommit.value, offset)?.let { selectCommit(it) }
    }

    fun selectCommit(commit: Commit) {
        _selectedCommit.value = commit

        // Fork-only: the arrow keys select commits faster than their diffs load, and the last one selected must be
        // the diff shown, so each load waits for the one before it to stop
        val previousJob = selectCommitJob
        selectCommitJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()

            try {
                val diffEntries = getCommitDiffEntriesUseCase(commit).okOrNull().orEmpty()
                val diffEntry = diffEntries.firstOrNull { entry ->
                    entry.filePath == this@HistoryViewModel.filePath
                }

                if (diffEntry == null) {
                    _viewDiffResult.value = ViewDiffResult.DiffNotFound(null)
                    return@launch
                }

                val diffType = DiffType.CommitDiff(diffEntry)
                val diffResult = getDiffUseCase(diffType, settings.diffTextViewType.first(), settings.diffDisplayFullFile.first())
                // Fork-only: GetDiffUseCase turns a cancellation into DiffNotFound, which would blank the diff until the
                // next commit's loads. And another commit's diff starts at its top, as in the diff pane
                ensureActive()
                lazyListState.value = LazyListState(0, 0)
                _viewDiffResult.value = diffResult
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
}

sealed class HistoryState(val filePath: String) {
    class Loading(filePath: String) : HistoryState(filePath)
    class Loaded(filePath: String, val commits: List<Commit>) : HistoryState(filePath)
    class Failed(filePath: String, val error: AppError) : HistoryState(filePath)
}

