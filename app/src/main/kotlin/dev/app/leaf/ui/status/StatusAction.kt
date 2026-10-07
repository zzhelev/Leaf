package dev.app.leaf.ui.status

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.AuthorInfo
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusSectionSizes
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
}