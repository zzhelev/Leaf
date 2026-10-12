// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.collectLatestInCoroutineScope
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.errOrNull
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.models.BranchComparison
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.usecases.GetBranchComparisonUseCase
import dev.app.leaf.domain.worktrees.branchToReview
import dev.app.leaf.domain.worktrees.name
import dev.app.leaf.extensions.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Repository

/** The review that "Review changes" opened (fork-only): [branch], which the worktree at [worktreePath] has, and [base]. */
data class WorktreeReviewTarget(val worktreePath: String, val branch: String, val base: String)

/**
 * What the review pane shows (fork-only, Phase 3.3): what [WorktreeReviewTarget.branch] would bring into the base.
 *
 * @param comparison the last comparison read, kept while a newer one loads.
 * @param uncommittedChanges the worktree's `git status` entries, which a merge leaves out. Null while unknown, or once
 * the worktree is gone.
 */
data class WorktreeReviewState(
    val target: WorktreeReviewTarget,
    val worktreeName: String,
    val isLoading: Boolean,
    val comparison: BranchComparison?,
    val error: AppError?,
    val viewState: FilesViewState,
    /** The visible rows of the changed files, for [viewState]. */
    val rows: List<FileRow<DiffEntry>>,
    val uncommittedChanges: Int?,
    val isTabWorktree: Boolean,
    val canSwitchTo: Boolean,
) {
    val branchName: String get() = Repository.shortenRefName(target.branch)
    val baseName: String get() = Repository.shortenRefName(target.base)
}

sealed interface WorktreeReviewAction {
    data class SelectEntry(val entry: DiffEntry) : WorktreeReviewAction
    data class ViewStateChanged(val viewState: FilesViewState) : WorktreeReviewAction
    data class TreeDirectoryToggle(val path: String) : WorktreeReviewAction
    data object Close : WorktreeReviewAction

    /** Shows the worktree's uncommitted changes: the tab's own, or the tab switches to the worktree. */
    data object ShowUncommittedChanges : WorktreeReviewAction
}

/** A comparison of the target, while it loads or once it did. */
private data class Comparing(
    val target: WorktreeReviewTarget,
    val isLoading: Boolean,
    val result: Either<BranchComparison, AppError>?,
)

/**
 * The review of a worktree's changes (fork-only). It's held next to the selection, which it sets to
 * [SelectedItem.None] while it's shown, rather than being a [SelectedItem] of its own, as Gitnuro's code handles those
 * in many places. Selecting anything else closes it.
 *
 * It reads the comparison again when the branch or the base moves, as an agent keeps committing.
 */
class WorktreeReviewViewModelExtender @AssistedInject constructor(
    private val getBranchComparisonUseCase: GetBranchComparisonUseCase,
    private val repositoryDataRepository: RepositoryDataRepository,
    @Assisted private val viewModelScope: CoroutineScope,
    @Assisted private val filesViewState: StateFlow<FilesViewState>,
    @Assisted private val selectedItem: StateFlow<SelectedItem>,
    @Assisted("onSelectItem") private val onSelectItem: (SelectedItem) -> Unit,
    @Assisted private val onDiffSelected: (DiffSelected) -> Unit,
    @Assisted private val onViewStateChanged: (FilesViewState) -> Unit,
    @Assisted("addCloseableView") private val addCloseableView: (CloseableView) -> Unit,
    @Assisted("removeCloseableView") private val removeCloseableView: (CloseableView) -> Unit,
) : CoroutineScope by viewModelScope {

    @AssistedFactory
    interface Factory {
        fun create(
            viewModelScope: CoroutineScope,
            filesViewState: StateFlow<FilesViewState>,
            selectedItem: StateFlow<SelectedItem>,
            @Assisted("onSelectItem") onSelectItem: (SelectedItem) -> Unit,
            onDiffSelected: (DiffSelected) -> Unit,
            onViewStateChanged: (FilesViewState) -> Unit,
            @Assisted("addCloseableView") addCloseableView: (CloseableView) -> Unit,
            @Assisted("removeCloseableView") removeCloseableView: (CloseableView) -> Unit,
        ): WorktreeReviewViewModelExtender
    }

    private val target = MutableStateFlow<WorktreeReviewTarget?>(null)

    /** What was selected when the review opened, which closing it selects again. */
    private var selectionBefore: SelectedItem? = null

    /** Folders closed in the folder tree, by full path. Reset when another review opens. */
    private val collapsedDirectories = MutableStateFlow(emptySet<String>())

    private val worktrees: Flow<WorktreeList?> = repositoryDataRepository.worktrees
        .map { it.dataOrNull() }
        .onStart { emit(null) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val comparing: Flow<Comparing?> = target.flatMapLatest { target ->
        if (target == null) {
            flowOf(null)
        } else {
            var last: Either<BranchComparison, AppError>? = null

            tipsOf(target).transformLatest {
                emit(Comparing(target, isLoading = true, result = last))

                val result = getBranchComparisonUseCase(target.base, target.branch)
                last = result

                emit(Comparing(target, isLoading = false, result = result))
            }
        }
    }

    val reviewState: StateFlow<WorktreeReviewState?> = combine(
        comparing,
        selectedItem,
        worktrees,
        filesViewState,
        collapsedDirectories,
    ) { comparing, selected, worktrees, viewState, collapsed ->
        if (comparing == null || selected != SelectedItem.None) {
            null
        } else {
            worktreeReviewState(comparing.target, comparing.isLoading, comparing.result, worktrees, viewState, collapsed)
        }
    }.stateIn(null as WorktreeReviewState?)

    init {
        // Selecting anything else closes the review
        selectedItem.collectLatestInCoroutineScope { selected ->
            if (selected != SelectedItem.None && target.value != null) {
                target.value = null
                selectionBefore = null
                removeCloseableView(CloseableView.WORKTREE_REVIEW)
            }
        }
    }

    /** Opens the review of [worktree]'s branch, compared with [base]. Nothing happens when there's nothing to review. */
    fun open(worktree: Worktree, base: String?) {
        val branch = worktree.branchToReview(base) ?: return
        val baseBranch = base ?: return
        val current = selectedItem.value

        if (current != SelectedItem.None) {
            selectionBefore = current
        }

        collapsedDirectories.value = emptySet()
        target.value = WorktreeReviewTarget(worktree.path, branch, baseBranch)
        onSelectItem(SelectedItem.None)
        addCloseableView(CloseableView.WORKTREE_REVIEW)
    }

    /** Closes the review, and selects again what was selected before it opened. */
    fun close() {
        if (target.value == null) return

        val before = selectionBefore ?: SelectedItem.UncommittedChanges

        target.value = null
        selectionBefore = null
        removeCloseableView(CloseableView.WORKTREE_REVIEW)
        onSelectItem(before)
    }

    /** Closes the review for the uncommitted changes, which the tab shows when nothing else is selected. */
    fun closeForUncommittedChanges() {
        selectionBefore = SelectedItem.UncommittedChanges
        close()
    }

    fun onAction(action: WorktreeReviewAction) {
        when (action) {
            is WorktreeReviewAction.SelectEntry ->
                onDiffSelected(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(action.entry))))

            is WorktreeReviewAction.ViewStateChanged -> onViewStateChanged(action.viewState)
            is WorktreeReviewAction.TreeDirectoryToggle -> collapsedDirectories.update {
                if (action.path in it) it - action.path else it + action.path
            }

            WorktreeReviewAction.Close -> close()
            // The view model switches the tab first, when it's another worktree's
            WorktreeReviewAction.ShowUncommittedChanges -> closeForUncommittedChanges()
        }
    }

    /** The commits that [target]'s branch and base point to. A change of either reads the comparison again. */
    private fun tipsOf(target: WorktreeReviewTarget): Flow<Pair<String?, String?>> = combine(
        repositoryDataRepository.localBranches.mapNotNull { it.dataOrNull() },
        repositoryDataRepository.remotes.mapNotNull { it.dataOrNull() },
    ) { localBranches, remotes ->
        val branches = localBranches + remotes.flatMap { it.branchesList }

        branches.firstOrNull { it.name == target.branch }?.hash to branches.firstOrNull { it.name == target.base }?.hash
    }.distinctUntilChanged()
}

/** The review pane's state for [target], from the comparison read so far and the worktree list. */
internal fun worktreeReviewState(
    target: WorktreeReviewTarget,
    isLoading: Boolean,
    result: Either<BranchComparison, AppError>?,
    worktrees: WorktreeList?,
    viewState: FilesViewState,
    collapsedDirectories: Set<String>,
): WorktreeReviewState {
    val info: WorktreeInfo? = worktrees?.worktrees?.firstOrNull { it.worktree.path == target.worktreePath }
    val comparison = result?.okOrNull()
    val status = info?.status

    return WorktreeReviewState(
        target = target,
        worktreeName = info?.worktree?.name ?: target.worktreePath.substringAfterLast('/'),
        isLoading = isLoading,
        comparison = comparison,
        error = result?.errOrNull(),
        viewState = viewState,
        rows = buildFileRows(
            files = comparison?.changes.orEmpty().toFileItems(),
            state = viewState,
            isCollapsed = collapsedDirectories::contains,
        ),
        uncommittedChanges = status?.let { it.staged + it.unstaged + it.untracked + it.conflicted },
        isTabWorktree = info?.worktree?.isCurrent == true,
        canSwitchTo = info?.worktree?.canSwitchTo == true,
    )
}
