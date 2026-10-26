// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.common.TabScope
import dev.app.leaf.ui.dialogs.ConfirmableAction.AbortOperation.Operation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * What keeps the changes pane open although the user folded it (fork-only). Only what can't be done without the pane:
 * - an [OperationInProgress]: Merge, Continue and Commit are in the pane's commit box and the conflicted files in its
 *   Unstaged list, so the repository can't wait for the user with the pane folded;
 * - a [Comparison], or a worktree review, which show only there;
 * - the [CommitEditor], whose panel with the messages is there (B5 of `docs/fork/commit-editor.md`).
 */
sealed interface ChangesPaneNeed {
    data class OperationInProgress(val operation: Operation) : ChangesPaneNeed

    data object Comparison : ChangesPaneNeed

    data object CommitEditor : ChangesPaneNeed
}

/**
 * The changes pane's fold and what holds it open. The pane shows while it isn't folded, and while something needs it
 * ([isHeld]); the saved choice isn't changed by that, so the pane folds again when the need ends.
 */
data class ChangesPaneState(
    val isFolded: Boolean,
    val need: ChangesPaneNeed? = null,
) {
    val isHeld: Boolean get() = need != null

    val isShown: Boolean get() = need != null || !isFolded
}

/**
 * The commit editor comes first, as its panel replaces the others while it shows. Then an operation in progress, before
 * a comparison: it is the one that waits for the user.
 */
fun changesPaneNeed(operation: Operation?, isComparing: Boolean, isEditingCommits: Boolean = false): ChangesPaneNeed? = when {
    isEditingCommits -> ChangesPaneNeed.CommitEditor
    operation != null -> ChangesPaneNeed.OperationInProgress(operation)
    isComparing -> ChangesPaneNeed.Comparison
    else -> null
}

/**
 * The need of the tab's changes pane, written by the repository view model and read by the Actions list, which is a view
 * model of its own and leaves its entry out while the pane is held (fork-only).
 */
@TabScope
class ChangesPaneNeeds @Inject constructor() {
    val need: StateFlow<ChangesPaneNeed?>
        field = MutableStateFlow<ChangesPaneNeed?>(null)

    fun set(newNeed: ChangesPaneNeed?) {
        need.value = newNeed
    }

    /** Folds the pane away or shows it again, unless something holds it open: then it does nothing, and says so. */
    fun toggleUnlessHeld(config: ChangesPaneConfig): Boolean {
        if (need.value != null) {
            return false
        }

        config.toggle()

        return true
    }
}
