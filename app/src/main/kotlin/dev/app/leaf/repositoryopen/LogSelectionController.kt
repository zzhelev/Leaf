// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.log.LogSelection
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.CloseableView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The range of commits selected in the log with Shift (fork-only, step A2 of `docs/fork/squash-and-rebase.md`). The
 * rules are in [LogSelection].
 *
 * While there's a range, the log's selection is its anchor, as a commit: a selection made anywhere else (a plain click,
 * the side panel, a comparison) ends it, and so do a refresh that loses either end, Esc and [clear].
 *
 * @param logRows the log's commits, newest first, as it shows them.
 * @param selectAnchor selects the commit as the log's, so that a range that starts from a branch or a tag picked in the
 * side panel starts from its commit.
 * @param onRangeStarted when a range starts: the commit's diff closes, as the list it was opened from doesn't show.
 */
class LogSelectionController(
    scope: CoroutineScope,
    private val logRows: StateFlow<List<String>>,
    private val selectedItem: StateFlow<SelectedItem>,
    private val selectAnchor: (String) -> Unit,
    private val onRangeStarted: () -> Unit,
    private val addCloseableView: (CloseableView) -> Unit,
    private val removeCloseableView: (CloseableView) -> Unit,
) {
    val selection: StateFlow<LogSelection>
        field = MutableStateFlow(LogSelection())

    init {
        // Both checks read the log and the selection as they are now, not as the change gave them: a collector can
        // hold a value while a range starts, and it would end the new range for an older log or selection
        scope.launch {
            logRows.collect { _ -> selection.update { it.prunedTo(logRows.value.toSet()) } }
        }

        // The anchor stays the log's selection: anything else selected ends the range
        scope.launch {
            selectedItem.collect { _ ->
                selection.update { range ->
                    if (range.anchor != null && range.anchor != selectedCommitItem()) LogSelection() else range
                }
            }
        }

        // Esc ends the range while there is one, the newest of the views it closes
        scope.launch {
            selection
                .map { it.anchor != null }
                .distinctUntilChanged()
                .collect { hasRange ->
                    if (hasRange) {
                        addCloseableView(CloseableView.LOG_SELECTION)
                    } else {
                        removeCloseableView(CloseableView.LOG_SELECTION)
                    }
                }
        }
    }

    /**
     * Shift-click on [commit]: the range from the anchor, or from the log's selected commit. Returns false when that
     * makes no range, for the caller to select the row as a plain click does.
     */
    fun extendTo(commit: String): Boolean {
        val range = selection.value.extendedTo(commit, selectedCommit())

        // Also when the log hasn't loaded the selected commit, such as a branch's picked in the side panel
        if (range.commitsIn(logRows.value).isEmpty()) {
            clear()
            return false
        }

        return start(range)
    }

    /**
     * Shift+↑ ([step] -1) or Shift+↓ (1): moves the range's lead a row. Returns the lead's row in the log's commits, to
     * bring into view, or null when nothing moved.
     */
    fun step(step: Int): Int? {
        val rows = logRows.value
        val stepped = selection.value.stepped(step, rows, selectedCommit()) ?: return null
        val lead = stepped.lead ?: selectedCommit()

        if (stepped.anchor != null) {
            start(stepped)
        } else {
            selection.value = stepped
        }

        return lead?.let(rows::indexOf)?.takeIf { it >= 0 }
    }

    /** Where the arrows without Shift move on from while there's a range: its lead. Null without one. */
    val lead: String?
        get() = selection.value.takeIf { it.anchor != null }?.lead

    fun clear() {
        selection.value = LogSelection()
    }

    /** Makes [range] the selection, with its anchor as the log's selected commit. False when it isn't a range. */
    private fun start(range: LogSelection): Boolean {
        val anchor = range.anchor

        if (anchor == null) {
            clear()
            return false
        }

        val wasRange = selection.value.anchor != null

        // The anchor first, so that the range never sees another commit selected
        selectAnchor(anchor)
        selection.value = range

        if (!wasRange) onRangeStarted()

        return true
    }

    /** The commit that the log has selected: a commit, or a branch's or tag's commit picked in the side panel. */
    private fun selectedCommit(): String? = (selectedItem.value as? SelectedItem.CommitBasedItem)?.commit?.hash

    /** The commit that the log has selected as a commit, which a range's anchor is. */
    private fun selectedCommitItem(): String? = (selectedItem.value as? SelectedItem.CommitItem)?.commit?.hash
}

/** The commits selected in the log with Shift, newest first, as the log shows them: two or more. */
data class SelectedCommits(val commits: List<Commit>) {
    val hashes: Set<String> = commits.mapTo(mutableSetOf()) { it.hash }
}
