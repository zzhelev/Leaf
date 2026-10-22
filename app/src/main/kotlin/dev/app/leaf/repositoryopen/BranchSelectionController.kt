// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.branches.BranchClick
import dev.app.leaf.domain.branches.BranchSelection
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.repositories.CloseableView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The local branches selected in the side panel for deleting several at once (fork-only, upstream issue 116). The
 * rules are in [BranchSelection].
 *
 * It outlives clicks in the log and in the other sections, and ends with Esc, a plain click on a branch, or its own
 * clear. A refresh drops the branches that are gone and leaves the rest, whatever their tips did meanwhile.
 *
 * @param localBranches every local branch, as the tab has them, not the ones a search shows.
 * @param loggedBranch the local branch that the log has selected, which a click with a modifier starts from.
 */
class BranchSelectionController(
    scope: CoroutineScope,
    private val localBranches: StateFlow<List<Branch>>,
    private val loggedBranch: () -> String?,
    private val addCloseableView: (CloseableView) -> Unit,
    private val removeCloseableView: (CloseableView) -> Unit,
) {
    val selection: StateFlow<BranchSelection>
        field = MutableStateFlow(BranchSelection())

    init {
        scope.launch {
            localBranches
                .map { branches -> branches.map { it.name } }
                .distinctUntilChanged()
                .collect { names -> selection.update { it.pruned(names) } }
        }

        // Esc ends the selection while there is one, the newest of the views it closes
        scope.launch {
            selection
                .map { it.selected.isNotEmpty() }
                .distinctUntilChanged()
                .collect { hasSelection ->
                    if (hasSelection) {
                        addCloseableView(CloseableView.BRANCH_SELECTION)
                    } else {
                        removeCloseableView(CloseableView.BRANCH_SELECTION)
                    }
                }
        }
    }

    /**
     * A click on [branch]. A plain click ends the selection, and returns true for the caller to select the branch in
     * the log, as it always did. The others change the selection and return false. [shown] are the local branches
     * that the list shows, in its order, for a range.
     */
    fun onClick(branch: String, click: BranchClick, shown: () -> List<String>): Boolean {
        when (click) {
            BranchClick.Plain -> selection.update { it.afterPlainClick(branch) }
            BranchClick.Toggle -> selection.update { it.afterToggle(branch, loggedBranch()) }
            BranchClick.Range -> selection.update { it.afterRange(branch, shown(), loggedBranch()) }
        }

        return click == BranchClick.Plain
    }

    /** "Select its branches" on a folder: [branches] are added to the selection. */
    fun add(branches: List<String>) {
        selection.update { it.afterAdding(branches, loggedBranch()) }
    }

    fun clear() {
        selection.update { BranchSelection() }
    }

    /** What the "Delete N branches" dialog is about: the selected branches, as the tab has them. */
    fun deletionRequest(): BranchesDeletionRequest {
        val selected = selection.value.selected

        return BranchesDeletionRequest(localBranches.value.filter { it.isLocal && it.name in selected })
    }

    /** What "Delete merged branches" is about: every local branch, of which the dialog lists the merged ones. */
    fun mergedDeletionRequest(): BranchesDeletionRequest =
        BranchesDeletionRequest(localBranches.value.filter { it.isLocal }, mergedOnly = true)
}
