// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.collectLatestInCoroutineScope
import dev.app.leaf.common.flows.combine
import dev.app.leaf.domain.compare.CompareSide
import dev.app.leaf.domain.compare.defaultMode
import dev.app.leaf.domain.compare.inPickedOrder
import dev.app.leaf.domain.compare.modeToShow
import dev.app.leaf.domain.compare.putsAncestorFirst
import dev.app.leaf.domain.compare.showsModeSwitch
import dev.app.leaf.domain.compare.swapped
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.errOrNull
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonRelation
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
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.GetComparisonUseCase
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry

/**
 * What a comparison compares (fork-only, `docs/fork/compare.md`): [a] and [b], or, with [reviewedWorktreePath], the
 * branch of the worktree that "Review changes" opened it for ([b]) against the base ([a]).
 */
data class ComparisonTarget(
    val a: CompareSide,
    val b: CompareSide,
    val reviewedWorktreePath: String? = null,
) {
    val isReview: Boolean get() = reviewedWorktreePath != null

    fun swapped() = copy(a = b, b = a)
}

/** A side that the user picked in the log, with its commit's date, which orders a new comparison's sides. */
data class PickedSide(val side: CompareSide, val date: Long)

/**
 * The worktree that "Review changes" opened the comparison for, as the worktree list has it.
 *
 * @param uncommittedChanges the worktree's `git status` entries, which a merge leaves out. Null while unknown, or once
 * the worktree is gone.
 */
data class ReviewedWorktree(
    val path: String,
    val name: String,
    val uncommittedChanges: Int?,
    val isTabWorktree: Boolean,
    val canSwitchTo: Boolean,
)

/**
 * One of the comparison's commits, whose own changes the file list shows (`docs/fork/compare.md`, 7.4): against its
 * first parent, as the log shows a commit's.
 *
 * @param position its place among its side's commits, oldest first, from 1, of [count].
 * @param isLoading while its changes are read. The list keeps what it showed until they come.
 */
data class ShownCommit(
    val commit: Commit,
    val position: Int,
    val count: Int,
    val isLoading: Boolean,
    val error: AppError?,
)

/**
 * What the comparison shows (fork-only).
 *
 * @param mode the mode that [comparison]'s changes are in, which the switch shows.
 * @param aCommit the commit that A is at now: a branch side follows its branch. The log marks this row.
 * @param comparison the last comparison read of these sides in this mode, kept while a newer one loads.
 * @param shownCommit the commit whose own changes the files are, in place of the comparison's.
 * @param files what the file list shows: the comparison's changes, or [shownCommit]'s.
 * @param review the worktree, for "Review changes" only.
 */
data class ComparisonState(
    val target: ComparisonTarget,
    val mode: ComparisonMode,
    val isLoading: Boolean,
    val comparison: Comparison?,
    val error: AppError?,
    val aCommit: String,
    val bCommit: String,
    val shownCommit: ShownCommit? = null,
    val files: List<DiffEntry> = comparison?.changes.orEmpty(),
    val viewState: FilesViewState,
    /** The visible rows of [files], for [viewState]. */
    val rows: List<FileRow<DiffEntry>>,
    val isFullView: Boolean,
    val review: ReviewedWorktree?,
) {
    val aName: String get() = target.a.name
    val bName: String get() = target.b.name

    /** The switch between the modes, for sides that diverged. */
    val showsModeSwitch: Boolean get() = comparison?.relation?.showsModeSwitch == true
}

sealed interface ComparisonAction {
    /** Shows [entry]'s diff. */
    data class SelectEntry(val entry: DiffEntry) : ComparisonAction

    /** Opens the full view at [entry], or at the file shown, or at the first file. */
    data class OpenFullView(val entry: DiffEntry? = null) : ComparisonAction

    /** Goes back from the full view to the log, with the comparison in the right pane. */
    data object LeaveFullView : ComparisonAction

    data class SetMode(val mode: ComparisonMode) : ComparisonAction
    data object Swap : ComparisonAction
    data class ViewStateChanged(val viewState: FilesViewState) : ComparisonAction
    data class TreeDirectoryToggle(val path: String) : ComparisonAction
    data object TryAgain : ComparisonAction
    data object End : ComparisonAction

    /** Lists [commit]'s own changes in place of the comparison's, or all of them again when it's the one shown. */
    data class ShowCommit(val commit: Commit) : ComparisonAction

    /** Shows the commit [offset] places from the one shown, among its side's commits: 1 is the next newer. */
    data class StepCommit(val offset: Int) : ComparisonAction

    /** Lists the comparison's changes again, in place of a commit's. */
    data object ShowAllChanges : ComparisonAction

    /** Shows the reviewed worktree's uncommitted changes: the tab's own, or the tab switches to the worktree. */
    data object ShowUncommittedChanges : ComparisonAction
}

/**
 * A comparison that runs.
 *
 * @param chosenMode the mode the user chose, or the one the comparison started in.
 * @param relation how the sides' histories meet, once a read told it.
 * @param orderSettled false until the first read: an ancestor that ended up as B goes first then.
 * @param shownCommit the commit whose own changes the files show.
 */
private data class Active(
    val target: ComparisonTarget,
    val chosenMode: ComparisonMode,
    val relation: ComparisonRelation?,
    val orderSettled: Boolean,
    val isFullView: Boolean,
    val tries: Int,
    val shownCommit: Commit? = null,
) {
    /**
     * The mode to read. The review asks for what the branch would bring, even when that's nothing. A comparison shows
     * all differences where since they split would list nothing.
     */
    val modeToRead: ComparisonMode
        get() = if (target.isReview) chosenMode else relation?.modeToShow(chosenMode) ?: chosenMode

    val read: Read get() = Read(target, modeToRead, tries)
}

/** One comparison to read. A change of any part starts a new read, without the last one's result. */
private data class Read(val target: ComparisonTarget, val mode: ComparisonMode, val tries: Int)

/** A read, while it loads or once it did, at [tips]. */
private data class Reading(
    val read: Read,
    val tips: Pair<String?, String?>,
    val isLoading: Boolean,
    val result: Either<Comparison, AppError>?,
)

/**
 * The read of a commit's own changes. While it loads, [shownBefore] are the files that the list showed, the last
 * commit's, which stay until these come.
 */
private data class CommitReading(
    val commit: Commit,
    val isLoading: Boolean,
    val result: Either<List<DiffEntry>, AppError>?,
    val shownBefore: List<DiffEntry>?,
)

/**
 * The comparison of two commits or branches, and the review of a worktree's changes (fork-only,
 * `docs/fork/compare.md`, step 2). It's held next to the selection, which it sets to [SelectedItem.None] while it's
 * shown, rather than being a [SelectedItem] of its own, as upstream's code handles those in many places. Selecting
 * anything else ends it.
 *
 * It reads the comparison again when a branch side moves, as an agent keeps committing.
 */
class ComparisonViewModelExtender @AssistedInject constructor(
    private val getComparisonUseCase: GetComparisonUseCase,
    private val getCommitDiffEntriesUseCase: GetCommitDiffEntriesUseCase,
    private val repositoryDataRepository: RepositoryDataRepository,
    @Assisted private val viewModelScope: CoroutineScope,
    @Assisted private val filesViewState: StateFlow<FilesViewState>,
    @Assisted private val selectedItem: StateFlow<SelectedItem>,
    @Assisted("onSelectItem") private val onSelectItem: (SelectedItem) -> Unit,
    /** The diff that the tab shows, which follows the file list when it changes. */
    @Assisted private val diffSelected: StateFlow<DiffSelected?>,
    @Assisted private val onDiffSelected: (DiffSelected) -> Unit,
    /** Closes the comparison's diff. */
    @Assisted("clearDiff") private val clearDiff: () -> Unit,
    @Assisted private val onViewStateChanged: (FilesViewState) -> Unit,
    @Assisted("addCloseableView") private val addCloseableView: (CloseableView) -> Unit,
    @Assisted("removeCloseableView") private val removeCloseableView: (CloseableView) -> Unit,
    /** What to select when a comparison ends on a side: the commit with this id, as one commit. */
    @Assisted private val selectionFor: (commit: String) -> SelectedItem,
) : CoroutineScope by viewModelScope {

    @AssistedFactory
    interface Factory {
        fun create(
            viewModelScope: CoroutineScope,
            filesViewState: StateFlow<FilesViewState>,
            selectedItem: StateFlow<SelectedItem>,
            @Assisted("onSelectItem") onSelectItem: (SelectedItem) -> Unit,
            diffSelected: StateFlow<DiffSelected?>,
            onDiffSelected: (DiffSelected) -> Unit,
            @Assisted("clearDiff") clearDiff: () -> Unit,
            onViewStateChanged: (FilesViewState) -> Unit,
            @Assisted("addCloseableView") addCloseableView: (CloseableView) -> Unit,
            @Assisted("removeCloseableView") removeCloseableView: (CloseableView) -> Unit,
            selectionFor: (commit: String) -> SelectedItem,
        ): ComparisonViewModelExtender
    }

    private val active = MutableStateFlow<Active?>(null)

    /** What was selected when the review opened, which ending it selects again. */
    private var selectionBefore: SelectedItem? = null

    /** Folders closed in the folder tree, by full path. Reset when another comparison starts. */
    private val collapsedDirectories = MutableStateFlow(emptySet<String>())

    /** The diff shown, to open the full view at it. */
    private var shownEntry: DiffEntry? = null

    private val worktrees: Flow<WorktreeList?> = repositoryDataRepository.worktrees
        .map { it.dataOrNull() }
        .onStart { emit(null) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reading: Flow<Reading?> = active
        .map { it?.read }
        .distinctUntilChanged()
        .flatMapLatest { read ->
            if (read == null) {
                flowOf(null)
            } else {
                var last: Either<Comparison, AppError>? = null

                tipsOf(read.target).transformLatest { tips ->
                    emit(Reading(read, tips, isLoading = true, result = last))

                    val result = getComparisonUseCase(read.target.a.revision, read.target.b.revision, read.mode)

                    // A new comparison may turn its sides around, or show the other mode, which reads it again. Then
                    // this result isn't emitted: the state, which combines the comparison and the read as they come,
                    // could show it with the comparison it came from for a moment
                    settle(read, result)
                    result.okOrNull()?.let { forgetShownCommitIfGone(it) }

                    if (active.value?.read == read) {
                        last = result
                        emit(Reading(read, tips, isLoading = false, result = result))
                    }
                }
            }
        }

    /** The shown commit's own changes, read again with Try again. The files shown before stay while they load. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val commitReading: Flow<CommitReading?> = flow {
        var shown: List<DiffEntry>? = null

        val readings = active
            .map { current -> current?.shownCommit?.let { it to current.tries } }
            .distinctUntilChanged()
            .transformLatest { key ->
                val commit = key?.first

                if (commit == null) {
                    shown = null
                    emit(null)
                } else {
                    emit(CommitReading(commit, isLoading = true, result = null, shownBefore = shown))

                    val result = getCommitDiffEntriesUseCase(commit)
                    shown = result.okOrNull()
                    emit(CommitReading(commit, isLoading = false, result = result, shownBefore = null))
                }
            }

        emitAll(readings)
    }

    val state: StateFlow<ComparisonState?> = combine(
        active,
        reading,
        commitReading,
        selectedItem,
        worktrees,
        filesViewState,
        collapsedDirectories,
    ) { active, reading, commitReading, selected, worktrees, viewState, collapsed ->
        if (active == null || selected != SelectedItem.None) {
            null
        } else {
            comparisonState(
                active = active,
                reading = reading?.takeIf { it.read == active.read },
                commitReading = commitReading?.takeIf { it.commit == active.shownCommit },
                worktrees = worktrees,
                viewState = viewState,
                collapsedDirectories = collapsed,
            )
        }
    }.stateIn(null as ComparisonState?)

    init {
        // Selecting anything else ends the comparison. The selection is read again: an older value can come after a
        // comparison started, which sets it to None first
        selectedItem.collectLatestInCoroutineScope {
            if (selectedItem.value != SelectedItem.None && active.value != null) {
                stop()
            }
        }

        // The diff of a file follows the list when it changes, to a commit's changes and back, or read again
        launch {
            state.map { it?.files }.distinctUntilChanged().drop(1).collect { files ->
                if (files != null) followFiles(files)
            }
        }
    }

    /** Opens the review of [worktree]'s branch, compared with [base]. Nothing happens when there's nothing to review. */
    fun openReview(worktree: Worktree, base: String?) {
        val branch = worktree.branchToReview(base) ?: return
        val baseBranch = base ?: return
        val target = ComparisonTarget(
            a = CompareSide.Branch(baseBranch, commit = ""),
            b = CompareSide.Branch(branch, commit = worktree.headSha.orEmpty()),
            reviewedWorktreePath = worktree.path,
        )

        start(target, ComparisonMode.SINCE_SPLIT, orderSettled = true)
    }

    /**
     * Compares two sides that the user picked in the log (⌘-click, "Compare with selected"): the older first, and an
     * ancestor first once git has compared them. The same commit twice compares nothing.
     */
    fun comparePicked(first: PickedSide, second: PickedSide) {
        if (first.side.commit == second.side.commit) return

        val (a, b) = inPickedOrder(first.side, first.date, second.side, second.date)

        start(ComparisonTarget(a, b), defaultMode(a, b), orderSettled = false)
    }

    /** Compares the current branch ([current]) with [other], from a menu: the current branch first, unless it's newer. */
    fun compareWithCurrent(current: CompareSide, other: CompareSide) {
        if (current.commit == other.commit) return

        start(ComparisonTarget(current, other), defaultMode(current, other), orderSettled = false)
    }

    /**
     * ⌘-click on a row while comparing. On A or B, it drops that side, and the other stays selected as one commit.
     * Elsewhere, the row replaces B: a new comparison with A.
     */
    fun pick(picked: PickedSide) {
        val current = state.value ?: return

        when (picked.side.commit) {
            current.aCommit -> end(selecting = current.target.b)
            current.bCommit -> end(selecting = current.target.a)
            else -> {
                val a = current.target.a
                start(ComparisonTarget(a, picked.side), defaultMode(a, picked.side), orderSettled = false)
            }
        }
    }

    /** Ends the comparison: the review selects again what was selected before it, any other selects A. */
    fun end() {
        val target = active.value?.target ?: return

        if (target.isReview) {
            end(selecting = null)
        } else {
            end(selecting = target.a)
        }
    }

    /** Ends the review for the uncommitted changes, which the tab shows when nothing else is selected. */
    fun endForUncommittedChanges() {
        if (active.value == null) return

        stop()
        onSelectItem(SelectedItem.UncommittedChanges)
    }

    fun leaveFullView() {
        if (active.value?.isFullView != true) return

        active.update { it?.copy(isFullView = false) }
        removeCloseableView(CloseableView.COMPARISON_FULL_VIEW)
    }

    fun onAction(action: ComparisonAction) {
        when (action) {
            is ComparisonAction.SelectEntry -> showEntry(action.entry)
            is ComparisonAction.OpenFullView -> openFullView(action.entry)
            ComparisonAction.LeaveFullView -> leaveFullView()
            is ComparisonAction.SetMode -> active.update { it?.copy(chosenMode = action.mode) }
            ComparisonAction.Swap -> active.update {
                it?.copy(target = it.target.swapped(), relation = it.relation?.swapped, orderSettled = true)
            }

            is ComparisonAction.ViewStateChanged -> onViewStateChanged(action.viewState)
            is ComparisonAction.TreeDirectoryToggle -> collapsedDirectories.update {
                if (action.path in it) it - action.path else it + action.path
            }

            ComparisonAction.TryAgain -> active.update { it?.copy(tries = it.tries + 1) }
            ComparisonAction.End -> end()
            is ComparisonAction.ShowCommit -> showCommit(action.commit)
            is ComparisonAction.StepCommit -> stepCommit(action.offset)
            ComparisonAction.ShowAllChanges -> showAllChanges()
            // The view model switches the tab first, when it's another worktree's
            ComparisonAction.ShowUncommittedChanges -> endForUncommittedChanges()
        }
    }

    private fun start(target: ComparisonTarget, mode: ComparisonMode, orderSettled: Boolean) {
        val current = selectedItem.value

        // A comparison that replaces another keeps what was selected before the first
        if (current != SelectedItem.None) {
            selectionBefore = current
        }

        collapsedDirectories.value = emptySet()
        shownEntry = null
        onSelectItem(SelectedItem.None)
        active.value = Active(target, mode, relation = null, orderSettled, isFullView = false, tries = 0)
        removeCloseableView(CloseableView.COMPARISON_FULL_VIEW)
        removeCloseableView(CloseableView.COMPARISON_COMMIT)
        addCloseableView(CloseableView.COMPARISON)
    }

    private fun end(selecting: CompareSide?) {
        if (active.value == null) return

        val before = selectionBefore ?: SelectedItem.UncommittedChanges
        val commit = selecting?.let { commitOf(it) }

        stop()
        onSelectItem(commit?.let(selectionFor) ?: before)
    }

    /** The commit that [side] is at now: a branch side follows its branch. */
    private fun commitOf(side: CompareSide): String {
        val shown = state.value ?: return side.commit

        return when (side) {
            shown.target.a -> shown.aCommit
            shown.target.b -> shown.bCommit
            else -> side.commit
        }
    }

    /** Ends the comparison without selecting anything. */
    private fun stop() {
        active.value = null
        selectionBefore = null
        shownEntry = null
        removeCloseableView(CloseableView.COMPARISON_FULL_VIEW)
        removeCloseableView(CloseableView.COMPARISON_COMMIT)
        removeCloseableView(CloseableView.COMPARISON)
    }

    private fun showEntry(entry: DiffEntry) {
        shownEntry = entry
        onDiffSelected(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(entry))))
    }

    private fun openFullView(entry: DiffEntry?) {
        val shown = state.value ?: return
        val files = shown.files
        val file = entry ?: shownEntry?.takeIf { it in files } ?: files.firstOrNull()

        if (file != null) {
            showEntry(file)
        }

        active.update { it?.copy(isFullView = true) }
        addCloseableView(CloseableView.COMPARISON_FULL_VIEW)
    }

    /**
     * Lists [commit]'s own changes, in place of the comparison's. A second click on the commit shown lists all of them
     * again, as "All changes" does.
     */
    fun showCommit(commit: Commit) {
        val current = active.value ?: return

        if (current.shownCommit?.hash == commit.hash) {
            showAllChanges()
            return
        }

        active.update { it?.copy(shownCommit = commit) }
        addCloseableView(CloseableView.COMPARISON_COMMIT)
    }

    /** Lists the comparison's changes again, in place of a commit's. */
    fun showAllChanges() {
        if (active.value?.shownCommit == null) return

        active.update { it?.copy(shownCommit = null) }
        removeCloseableView(CloseableView.COMPARISON_COMMIT)
    }

    /** Shows the commit [offset] places from the one shown, among its side's commits, oldest first. */
    private fun stepCommit(offset: Int) {
        val shown = active.value?.shownCommit ?: return
        val commits = state.value?.comparison?.commitsOf(shown) ?: return
        val index = commits.indexOfFirst { it.hash == shown.hash }
        val next = commits.getOrNull(index + offset)?.takeIf { index >= 0 } ?: return

        active.update { it?.copy(shownCommit = next) }
    }

    /** A commit that a new read no longer lists, as after a rebase of a branch side, stops being shown. */
    private fun forgetShownCommitIfGone(comparison: Comparison) {
        val shown = active.value?.shownCommit ?: return

        if (comparison.commitsOf(shown) == null) {
            showAllChanges()
        }
    }

    /**
     * Moves the comparison's diff to [files], the list as it is now: to the entry of the same file, or, in the full view,
     * which always shows one, to the first. A diff whose file isn't listed closes.
     */
    private fun followFiles(files: List<DiffEntry>) {
        val entry = shownEntry ?: return
        val shownNow = (diffSelected.value as? DiffSelected.CommitedChanges)?.items?.singleOrNull()?.diffEntry

        // Only a diff that the comparison opened
        if (shownNow !== entry) return

        val next = files.firstOrNull { it.oldPath == entry.oldPath && it.newPath == entry.newPath }
            ?: files.firstOrNull { it.newPath == entry.newPath && it.newPath != DiffEntry.DEV_NULL }
            ?: files.firstOrNull { it.oldPath == entry.oldPath && it.oldPath != DiffEntry.DEV_NULL }
            ?: files.firstOrNull()?.takeIf { active.value?.isFullView == true }

        if (next != null) {
            // The same versions of the same file keep the diff as it is, where it was scrolled to
            if (next.oldPath != entry.oldPath || next.newPath != entry.newPath || next.oldId != entry.oldId ||
                next.newId != entry.newId
            ) {
                showEntry(next)
            }
        } else {
            shownEntry = null
            clearDiff()
        }
    }

    /**
     * Takes in what [read] learnt: how the sides' histories meet. A new comparison whose B is A's ancestor turns around
     * (the review starts settled, as its base comes first), and a comparison that should show the other mode reads it.
     */
    private fun settle(read: Read, result: Either<Comparison, AppError>) {
        val relation = result.okOrNull()?.relation ?: return

        active.update { current ->
            if (current == null || current.read != read) {
                current
            } else if (!current.orderSettled && relation.putsAncestorFirst) {
                current.copy(target = current.target.swapped(), relation = relation.swapped, orderSettled = true)
            } else {
                current.copy(relation = relation, orderSettled = true)
            }
        }
    }

    /**
     * The commits that [target]'s sides are at: a branch's tip in the tab's branch data, which a change of reads the
     * comparison again; a commit's own. A branch that's gone is null.
     */
    private fun tipsOf(target: ComparisonTarget): Flow<Pair<String?, String?>> = combine(
        repositoryDataRepository.localBranches.mapNotNull { it.dataOrNull() },
        repositoryDataRepository.remotes.mapNotNull { it.dataOrNull() },
    ) { localBranches, remotes ->
        val branches = localBranches + remotes.flatMap { it.branchesList }

        fun tipOf(side: CompareSide): String? = when (side) {
            is CompareSide.Branch -> branches.firstOrNull { it.name == side.ref }?.hash
            is CompareSide.Commit -> side.commit
        }

        tipOf(target.a) to tipOf(target.b)
    }.distinctUntilChanged()
}

/** The commits of the side that has [commit], oldest first, or null when neither lists it. */
private fun Comparison.commitsOf(commit: Commit): List<Commit>? = listOf(commitsOnlyInB, commitsOnlyInA)
    .firstOrNull { commits -> commits.any { it.hash == commit.hash } }

/**
 * The comparison's state, from the active comparison, its read so far, the read of the commit shown and the worktree
 * list.
 */
private fun comparisonState(
    active: Active,
    reading: Reading?,
    commitReading: CommitReading?,
    worktrees: WorktreeList?,
    viewState: FilesViewState,
    collapsedDirectories: Set<String>,
): ComparisonState {
    val result = reading?.result
    val comparison = result?.okOrNull()
    val target = active.target
    val shownCommit = active.shownCommit?.let { commit ->
        val commits = comparison?.commitsOf(commit)

        ShownCommit(
            commit = commit,
            position = (commits?.indexOfFirst { it.hash == commit.hash } ?: -1) + 1,
            count = commits?.size ?: 0,
            isLoading = commitReading?.result == null,
            error = commitReading?.result?.errOrNull(),
        )
    }
    val files = when {
        shownCommit == null -> comparison?.changes.orEmpty()
        commitReading?.result != null -> commitReading.result.okOrNull().orEmpty()
        // While a commit's changes load, the list keeps what it showed
        else -> commitReading?.shownBefore ?: comparison?.changes.orEmpty()
    }

    return ComparisonState(
        target = target,
        mode = active.modeToRead,
        isLoading = reading == null || reading.isLoading || shownCommit?.isLoading == true,
        comparison = comparison,
        error = result?.errOrNull(),
        aCommit = reading?.tips?.first ?: target.a.commit,
        bCommit = reading?.tips?.second ?: target.b.commit,
        shownCommit = shownCommit,
        files = files,
        viewState = viewState,
        rows = buildFileRows(
            files = files.toFileItems(),
            state = viewState,
            isCollapsed = collapsedDirectories::contains,
        ),
        isFullView = active.isFullView,
        review = target.reviewedWorktreePath?.let { reviewedWorktree(it, worktrees) },
    )
}

/** The reviewed worktree at [path], as the worktree list has it. */
internal fun reviewedWorktree(path: String, worktrees: WorktreeList?): ReviewedWorktree {
    val info: WorktreeInfo? = worktrees?.worktrees?.firstOrNull { it.worktree.path == path }
    val status = info?.status

    return ReviewedWorktree(
        path = path,
        name = info?.worktree?.name ?: path.substringAfterLast('/'),
        uncommittedChanges = status?.let { it.staged + it.unstaged + it.untracked + it.conflicted },
        isTabWorktree = info?.worktree?.isCurrent == true,
        canSwitchTo = info?.worktree?.canSwitchTo == true,
    )
}
