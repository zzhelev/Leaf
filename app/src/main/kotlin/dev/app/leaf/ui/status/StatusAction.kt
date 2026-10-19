package dev.app.leaf.ui.status

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.AuthorInfo
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.domain.sorting.DiscardAllPlan
import dev.app.leaf.domain.sorting.FilesViewState

sealed interface StatusAction {
    data class EntryAction(val statusEntry: StatusEntry) : StatusAction
    data class AllEntriesAction(val entryType: EntryType) : StatusAction
    data class Reset(val statusEntry: StatusEntry) : StatusAction
    data class Delete(val statusEntry: StatusEntry) : StatusAction
    data class SelectEntry(
        val statusEntry: StatusEntry,
        val isCtrlPressed: Boolean,
        val isMetaPressed: Boolean,
        val isShiftPressed: Boolean,
        /** The files of the pane in the order shown, for Shift ranges. */
        val diffEntries: List<StatusEntry>,
        val selectedEntries: List<DiffType.UncommittedDiff>,
    ) : StatusAction
    data class DiscardSelected(val entryType: EntryType) : StatusAction

    /** "Discard all" in Unstaged: restores what [plan] lists, and with [deleteNewFiles] deletes its new files (fork-only). */
    data class DiscardAll(val plan: DiscardAllPlan, val deleteNewFiles: Boolean) : StatusAction
    data class SelectedEntriesAction(val entryType: EntryType) : StatusAction
    data class OpenInFolder(val path: String) : StatusAction
    data class TreeDirectoryToggle(val path: String, val entryType: EntryType) : StatusAction
    data class ViewStateChanged(val viewState: FilesViewState) : StatusAction
    data class DirectoryAction(val path: String, val entryType: EntryType) : StatusAction

    /** Stages or unstages what a folder row shows: the whole directory, or its search matches during a search. */
    data class FolderRowAction(val path: String, val entryType: EntryType) : StatusAction
    data class SearchFilterChanged(val filter: TextFieldValue, val entryType: EntryType) : StatusAction
    data class Commit(val message: String) : StatusAction
    data class ContinueRebase(val message: String) : StatusAction
    data object SkipRebase : StatusAction
    data object AbortRebase : StatusAction
    data object ResetRepositoryState : StatusAction

    /** Undoes the squash merge in progress, conflicts included (fork-only). */
    data object AbortSquashMerge : StatusAction
    data class UpdateCommitMessage(val message: TextFieldValue) : StatusAction

    data object RejectCommitterData : StatusAction
    data class AcceptCommitterData(val authorInfo: AuthorInfo, val persist: Boolean) : StatusAction
    data class SearchFilterToggledStaged(val show: Boolean): StatusAction
    data class SearchFilterToggledUnstaged(val show: Boolean): StatusAction
    data object AddStagedSearchToCloseableView: StatusAction
    data object AddUnstagedSearchToCloseableView: StatusAction

    data class ToggleAmend(val toggle: Boolean): StatusAction
    data class ToggleAmendRebaseInteractive(val toggle: Boolean): StatusAction

    /** The user finished resizing the pane's sections. */
    data class SectionSizesChanged(val sizes: StatusSectionSizes) : StatusAction

    /** Resolves a conflicted file in the user's merge tool, with `git mergetool` (fork-only). */
    data class OpenInExternalMergeTool(val statusEntry: StatusEntry) : StatusAction

    /** Resolves [filePaths] in the user's merge tool one after another, as `git mergetool` does (fork-only). */
    data class OpenInExternalMergeToolInTurn(val filePaths: List<String>) : StatusAction

    /** Resolves conflicted files by taking [side]'s version of each, once the user confirmed it (fork-only). */
    data class UseConflictSide(val filePaths: List<String>, val side: ConflictSide) : StatusAction

    /** Opens a conflicted file in the built-in resolver, which `RepositoryOpenViewModel` shows (fork-only). */
    data class OpenInResolver(val statusEntry: StatusEntry) : StatusAction
}