// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.repositoryopen.matchesSearch
import dev.app.leaf.repositoryopen.toFileItems
import org.eclipse.jgit.diff.DiffEntry

/**
 * The files that the commit being amended changes, against its first parent (fork-only, upstream issue 260). The amended
 * commit is its tree plus what is staged, and Staged lists only what is staged, so this is what the user can't see
 * otherwise. [changes] keeps the files of the commit before while another one's load, and is empty on the first load.
 */
data class AmendedFiles(
    val commit: Commit,
    val changes: List<DiffEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: AppError? = null,
)

/**
 * The commit that the next commit amends, or null when it doesn't amend one: the last commit while Amend is on, and at
 * an interactive rebase's amendable stop (where it is HEAD) while that stop's own switch is. The same rule as the commit
 * message's "Amend last commit" item, which [commitMessageHeader] holds.
 */
fun amendedCommit(
    repositoryState: RepositoryState,
    rebaseInteractiveState: RebaseInteractiveState,
    isAmend: Boolean,
    isAmendRebaseInteractive: Boolean,
    head: Commit?,
): Commit? = head?.takeIf {
    commitMessageHeader(
        repositoryState,
        isAmenableRebaseInteractive(repositoryState, rebaseInteractiveState),
        isAmend,
        isAmendRebaseInteractive,
    ).amend?.isOn == true
}

/**
 * What the Staged pane lists after its own rows while Amend is on: [rows] built as Staged's are (the same sort, view and
 * search), [count] of the files they stand for, and whether the group is [isCollapsed] to its header.
 */
data class AmendedGroup(
    val commit: Commit,
    val count: Int,
    val rows: List<FileRow<DiffEntry>>,
    val isCollapsed: Boolean,
    val isLoading: Boolean,
    val error: AppError?,
) {
    /** The header's count: not while the first load runs, when the files aren't known yet. */
    val showsCount: Boolean get() = error == null && (!isLoading || count > 0)
}

/**
 * The group for [files], or null while Amend is off. [searchFilter] is Staged's search text, or null when it isn't
 * searching: the files shown are the matches, and a search with none leaves the group out, as Staged shows no row then.
 * Its folders close apart from Staged's ([folders]).
 */
fun amendedGroup(
    files: AmendedFiles?,
    viewState: FilesViewState,
    searchFilter: String?,
    isCollapsed: Boolean,
    folders: CollapsedFolders,
): AmendedGroup? {
    files ?: return null

    val isSearching = searchFilter != null
    val shown = if (searchFilter == null) files.changes else files.changes.filter { it.matchesSearch(searchFilter) }

    if (isSearching && shown.isEmpty()) return null

    return AmendedGroup(
        commit = files.commit,
        count = shown.size,
        rows = buildFileRows(shown.toFileItems(), viewState) { path -> folders.isCollapsed(path, isSearching) },
        isCollapsed = isCollapsed,
        isLoading = files.isLoading,
        error = files.error,
    )
}

/**
 * Whether the diff of [shown] has to close: it was opened from the group, whose last files were [listedBefore], and the
 * group no longer has that file in [listedNow] (Amend is off, the commit was made, or HEAD moved to another commit), or
 * the uncommitted changes aren't selected any more ([onUncommittedChanges]), so the pane that held the group is gone.
 * A diff opened from anywhere else is never this one's to close. Files are the same by identity: another read gives new
 * entries, even for the same commit.
 */
fun isAmendedFileDiffStale(
    shown: DiffEntry?,
    listedBefore: List<DiffEntry>,
    listedNow: List<DiffEntry>,
    onUncommittedChanges: Boolean,
): Boolean = shown != null &&
        listedBefore.any { it === shown } &&
        (!onUncommittedChanges || listedNow.none { it === shown })
