package dev.app.leaf.ui.status

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.common.flows.combine
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.commitmessage.CommitMessageLayout
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.commitmessage.ConventionalHistory
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.commitmessage.commitMessageLayout
import dev.app.leaf.domain.extensions.lowercaseContains
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.sorting.inFolder
import dev.app.leaf.domain.sorting.toFileItems
import dev.app.leaf.ui.UiDataState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

private const val TAG = "StatusState"

sealed interface SelectionType<T> {
    data class SetSingleEntry<T>(val entry: T) : SelectionType<T>
    data class AppendSingleEntry<T>(val entry: T) : SelectionType<T>
    data class RemoveSingleEntry<T>(val entry: T) : SelectionType<T>
    data class AddMultipleEntries<T>(val entries: List<T>) : SelectionType<T>
}


@Immutable
data class StatusState(
    val isLoading: Boolean = true,
    /** Every staged entry, one per path. */
    val staged: List<StatusEntry> = emptyList(),
    /** Every unstaged entry, one per path, keeping the conflicting one when git reports a path twice. */
    val unstaged: List<StatusEntry> = emptyList(),
    /** The rows of the Staged and Unstaged panes, filtered by their searches. */
    val stagedRows: List<FileRow<StatusEntry>> = emptyList(),
    val unstagedRows: List<FileRow<StatusEntry>> = emptyList(),
    val swapUncommittedChanges: Boolean = false,
    val isAmend: Boolean = false,
    val isAmendRebaseInteractive: Boolean = false,
    val committerDataRequestState: CommitterDataRequestState = CommitterDataRequestState.None,
    val rebaseInteractiveState: RebaseInteractiveState = RebaseInteractiveState.None,
    val selectedUnstagedDiffEntries: List<DiffType.UncommittedDiff> = emptyList(),
    val selectedStagedDiffEntries: List<DiffType.UncommittedDiff> = emptyList(),
    val showSearchStaged: Boolean = false,
    val searchFilterStaged: TextFieldValue = TextFieldValue(""),
    val showSearchUnstaged: Boolean = false,
    val searchFilterUnstaged: TextFieldValue = TextFieldValue(""),
    val viewState: FilesViewState = FilesViewState(),
    val commitMessage: TextFieldValue = TextFieldValue(""),
    val previousCommitMessage: String? = null,
    val repositoryState: RepositoryState = RepositoryState.SAFE,
    val repositoryPath: String? = null,
    val sectionSizes: StatusSectionSizes = StatusSectionSizes(),
    /** What ours and theirs are, while files have conflicts (fork-only). */
    val conflictSides: ConflictSides? = null,
    /** A squash merge waits to be committed (fork-only), which the repository state doesn't tell. */
    val isSquashMergeInProgress: Boolean = false,
    /** How git cleans up the message before committing it (fork-only): `commit.cleanup` and the comment prefix. */
    val messageCleanup: MessageCleanup = MessageCleanup.DEFAULT,
    /** The commit field's helpers that the settings turn on (fork-only). */
    val messageHints: CommitMessageHints = CommitMessageHints(),
    /** Whom the commit field suggests as co-authors, and its "Add co-author" line (fork-only). */
    val coAuthors: CoAuthorState = CoAuthorState(),
    /** Whether Leaf adds `Signed-off-by` when committing: the repository's sign-off setting (fork-only). */
    val isSignOff: Boolean = false,
    /** The Conventional Commits types and scopes of the loaded history, for the commit field's suggestions (fork-only). */
    val conventionalHistory: ConventionalHistory = ConventionalHistory(),
    /** The worktree's commitlint config, which narrows those suggestions, or null (fork-only). */
    val commitlintRules: CommitlintRules? = null,
) {
    /** What the commit field shows about its message, as git will commit it (fork-only). */
    val messageLayout: CommitMessageLayout = commitMessageLayout(commitMessage.text, messageCleanup)

    val hasPreviousCommits: Boolean = previousCommitMessage != null

    val haveConflictsBeenSolved: Boolean = unstaged.none { it.statusType == StatusType.CONFLICTING }

    fun getEntriesByEntryType(entryType: EntryType): List<StatusEntry> {
        return when (entryType) {
            EntryType.STAGED -> staged
            EntryType.UNSTAGED -> unstaged
        }
    }

    /**
     * The search text that narrows [entryType]'s list, or null when the pane isn't searching (fork-only). What a bulk
     * action acts on is the files shown, as with a folder row.
     */
    fun activeSearchOf(entryType: EntryType): String? = when (entryType) {
        EntryType.STAGED -> activeSearch(showSearchStaged, searchFilterStaged)
        EntryType.UNSTAGED -> activeSearch(showSearchUnstaged, searchFilterUnstaged)
    }

    /** The files that [entryType]'s pane lists: all of them, or only the search matches while it searches (fork-only). */
    fun entriesShown(entryType: EntryType): List<StatusEntry> =
        getEntriesByEntryType(entryType).filteredBySearch(activeSearchOf(entryType))

    /**
     * The files a folder row stands for: every file under [folderPath], including those inside closed folders, or only
     * the search matches while the pane is searching.
     */
    fun entriesShownUnder(folderPath: String, entryType: EntryType): List<StatusEntry> {
        val searchFilter = when (entryType) {
            EntryType.STAGED -> activeSearch(showSearchStaged, searchFilterStaged)
            EntryType.UNSTAGED -> activeSearch(showSearchUnstaged, searchFilterUnstaged)
        }

        return getEntriesByEntryType(entryType)
            .filteredBySearch(searchFilter)
            .inFolder(folderPath)
    }

    val hasStagedFiles = staged.isNotEmpty()
    val hasUnstagedFiles = unstaged.isNotEmpty()

    /** The files with uncommitted changes, staged or not. */
    val changedFilesCount = (staged + unstaged).map { it.filePath }.distinct().count()
}

/**
 * What the Staged and Unstaged panes list. Built apart from the rest of [StatusState], so that what changes often, such
 * as each letter typed in the commit message, doesn't sort and group every changed file again.
 */
private class StatusLists(
    val statusDataState: UiDataState<Status>,
    val staged: List<StatusEntry>,
    val unstaged: List<StatusEntry>,
    val stagedRows: List<FileRow<StatusEntry>>,
    val unstagedRows: List<FileRow<StatusEntry>>,
    val showSearchStaged: Boolean,
    val searchFilterStaged: TextFieldValue,
    val showSearchUnstaged: Boolean,
    val searchFilterUnstaged: TextFieldValue,
    val viewState: FilesViewState,
)

fun combineStatusState(
    status: Flow<UiDataState<Status>>,
    showSearchStaged: MutableStateFlow<Boolean>,
    searchFilterStaged: MutableStateFlow<TextFieldValue>,
    showSearchUnstaged: MutableStateFlow<Boolean>,
    searchFilterUnstaged: MutableStateFlow<TextFieldValue>,
    viewState: Flow<FilesViewState>,
    stagedCollapsedFolders: Flow<CollapsedFolders>,
    unstagedCollapsedFolders: Flow<CollapsedFolders>,
    swapUncommittedChanges: Flow<Boolean>,
    isAmend: Flow<Boolean>,
    isAmendRebaseInteractive: Flow<Boolean>,
    committerDataRequestState: Flow<CommitterDataRequestState>,
    rebaseInteractiveState: Flow<RebaseInteractiveState>,
    selectedUnstagedDiffEntries: Flow<List<DiffType.UncommittedDiff>>,
    selectedStagedDiffEntries: Flow<List<DiffType.UncommittedDiff>>,
    commitMessage: Flow<TextFieldValue>,
    previousCommitMessage: Flow<String?>,
    repositoryState: Flow<UiDataState<RepositoryState>>,
    repositoryPath: Flow<String?>,
    sectionSizes: Flow<StatusSectionSizes>,
    conflictSides: Flow<ConflictSides?> = flowOf(null),
    squashMergeInProgress: Flow<Boolean> = flowOf(false),
    messageCleanup: Flow<MessageCleanup> = flowOf(MessageCleanup.DEFAULT),
    messageHints: Flow<CommitMessageHints> = flowOf(CommitMessageHints()),
    coAuthors: Flow<CoAuthorState> = flowOf(CoAuthorState()),
    isSignOff: Flow<Boolean> = flowOf(false),
    conventionalHistory: Flow<ConventionalHistory> = flowOf(ConventionalHistory()),
    commitlintRules: Flow<CommitlintRules?> = flowOf(null),
): Flow<StatusState> {
    val lists = combine(
        status,
        showSearchStaged,
        searchFilterStaged,
        showSearchUnstaged,
        searchFilterUnstaged,
        viewState,
        stagedCollapsedFolders,
        unstagedCollapsedFolders,
    ) {
            statusDataState,
            showSearchStaged,
            searchFilterStaged,
            showSearchUnstaged,
            searchFilterUnstaged,
            viewState,
            stagedCollapsedFolders,
            unstagedCollapsedFolders,
        ->
        val status = statusDataState.data ?: Status()
        val staged = status.staged.prioritizeConflicts()
        val unstaged = status.unstaged.prioritizeConflicts()

        StatusLists(
            statusDataState = statusDataState,
            staged = staged,
            unstaged = unstaged,
            stagedRows = statusPaneRows(
                staged,
                viewState,
                searchFilter = activeSearch(showSearchStaged, searchFilterStaged),
                stagedCollapsedFolders,
            ),
            unstagedRows = statusPaneRows(
                unstaged,
                viewState,
                searchFilter = activeSearch(showSearchUnstaged, searchFilterUnstaged),
                unstagedCollapsedFolders,
            ),
            showSearchStaged = showSearchStaged,
            searchFilterStaged = searchFilterStaged,
            showSearchUnstaged = showSearchUnstaged,
            searchFilterUnstaged = searchFilterUnstaged,
            viewState = viewState,
        )
    }

    return combine(
        lists,
        swapUncommittedChanges,
        isAmend,
        isAmendRebaseInteractive,
        committerDataRequestState,
        rebaseInteractiveState,
        selectedUnstagedDiffEntries,
        selectedStagedDiffEntries,
        commitMessage,
        previousCommitMessage,
        repositoryState,
        repositoryPath,
        sectionSizes,
        conflictSides,
        squashMergeInProgress,
        messageCleanup,
        messageHints,
        coAuthors,
        isSignOff,
        conventionalHistory,
        commitlintRules,
    ) {
            lists,
            swapUncommittedChanges,
            isAmend,
            isAmendRebaseInteractive,
            committerDataRequestState,
            rebaseInteractiveState,
            selectedUnstagedDiffEntries,
            selectedStagedDiffEntries,
            commitMessage,
            previousCommitMessage,
            repositoryStateDateState,
            repositoryPath,
            sectionSizes,
            conflictSides,
            squashMergeInProgress,
            messageCleanup,
            messageHints,
            coAuthors,
            isSignOff,
            conventionalHistory,
            commitlintRules,
        ->
        val repositoryState = repositoryStateDateState.data ?: RepositoryState.SAFE
        val isLoading = lists.statusDataState.isLoading || repositoryStateDateState.isLoading

        StatusState(
            isLoading = isLoading,
            staged = lists.staged,
            unstaged = lists.unstaged,
            stagedRows = lists.stagedRows,
            unstagedRows = lists.unstagedRows,
            swapUncommittedChanges = swapUncommittedChanges,
            isAmend = isAmend,
            isAmendRebaseInteractive = isAmendRebaseInteractive,
            committerDataRequestState = committerDataRequestState,
            rebaseInteractiveState = rebaseInteractiveState,
            selectedUnstagedDiffEntries = selectedUnstagedDiffEntries,
            selectedStagedDiffEntries = selectedStagedDiffEntries,
            showSearchStaged = lists.showSearchStaged,
            searchFilterStaged = lists.searchFilterStaged,
            showSearchUnstaged = lists.showSearchUnstaged,
            searchFilterUnstaged = lists.searchFilterUnstaged,
            viewState = lists.viewState,
            commitMessage = commitMessage,
            previousCommitMessage = previousCommitMessage,
            repositoryState = repositoryState,
            repositoryPath = repositoryPath,
            sectionSizes = sectionSizes,
            conflictSides = conflictSides,
            isSquashMergeInProgress = squashMergeInProgress,
            messageCleanup = messageCleanup,
            messageHints = messageHints,
            coAuthors = coAuthors,
            isSignOff = isSignOff,
            conventionalHistory = conventionalHistory,
            commitlintRules = commitlintRules,
        )
    }
}

/** The rows of one pane. [searchFilter] is the search text, or null when the pane isn't searching. */
private fun statusPaneRows(
    entries: List<StatusEntry>,
    viewState: FilesViewState,
    searchFilter: String?,
    collapsedFolders: CollapsedFolders,
): List<FileRow<StatusEntry>> {
    val isSearching = searchFilter != null

    return buildFileRows(entries.filteredBySearch(searchFilter).toFileItems(), viewState) { path ->
        collapsedFolders.isCollapsed(path, isSearching)
    }
}

/** A pane's search text, or null when the pane isn't searching. */
private fun activeSearch(showSearch: Boolean, searchFilter: TextFieldValue): String? =
    searchFilter.text.takeIf { showSearch && it.isNotBlank() }

private fun List<StatusEntry>.filteredBySearch(searchFilter: String?): List<StatusEntry> =
    if (searchFilter == null) this else filter { it.filePath.lowercaseContains(searchFilter) }

private fun List<StatusEntry>.prioritizeConflicts(): List<StatusEntry> {
    return this.groupBy { it.filePath }
        .map {
            val statusEntries = it.value
            return@map if (statusEntries.count() == 1) {
                statusEntries.first()
            } else {
                val conflictingEntry =
                    statusEntries.firstOrNull { entry -> entry.statusType == StatusType.CONFLICTING }

                conflictingEntry ?: statusEntries.first()
            }
        }
}

sealed interface CommitterDataRequestState {
    data object None : CommitterDataRequestState
    data class WaitingInput(val authorInfo: AuthorInfo) : CommitterDataRequestState
    data class Accepted(val authorInfo: AuthorInfo, val persist: Boolean) : CommitterDataRequestState
    object Reject : CommitterDataRequestState
}

/**
 * Fork-only: waits until the user accepts or rejects the commit identity dialog. It suspends; a loop that read the
 * value kept a thread busy while the dialog was open, and forever when the tab closed.
 */
suspend fun Flow<CommitterDataRequestState>.awaitAnswer(): CommitterDataRequestState =
    first { it !is CommitterDataRequestState.WaitingInput }
