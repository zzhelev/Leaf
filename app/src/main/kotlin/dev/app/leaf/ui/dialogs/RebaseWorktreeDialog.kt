// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRebase
import dev.app.leaf.domain.models.WorktreeRebaseRefusal
import dev.app.leaf.domain.worktrees.name
import dev.app.leaf.ui.baseBranchName
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 440.dp

/**
 * Confirms rebasing a worktree's branch onto the base (fork-only). It says how many commits the rebase replays, warns
 * about an agent's recent changes, and shows the commands that run.
 */
@Composable
fun RebaseWorktreeDialog(
    viewModel: RebaseWorktreeViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    RebaseWorktreeDialogContent(
        state = state,
        worktree = viewModel.worktree,
        base = viewModel.base,
        onConfirm = {
            if (viewModel.confirm()) {
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The dialog for [state], which is read for [worktree]'s branch and [base]: their names show until it's read.
 * [onConfirm] runs the rebase.
 */
@Composable
internal fun RebaseWorktreeDialogContent(
    state: RebaseWorktreeState,
    worktree: Worktree,
    base: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rebase = state.rebase
    val baseName = baseBranchName(rebase?.base ?: base)
    val branchName = baseBranchName(rebase?.branch ?: worktree.branch.orEmpty())

    val subtitle = when {
        rebase == null -> stringResource(Res.string.rebase_worktree_dialog_subtitle_loading, branchName, baseName)
        rebase.commitCount == 0 -> stringResource(
            Res.string.rebase_worktree_dialog_fast_forward_subtitle,
            branchName,
            baseName,
        )

        else -> pluralStringResource(
            Res.plurals.rebase_worktree_dialog_subtitle,
            rebase.commitCount,
            branchName,
            rebase.commitCount,
            pluralStringResource(
                Res.plurals.rebase_worktree_dialog_base_commits,
                rebase.baseCommitCount,
                baseName,
                rebase.baseCommitCount,
            ),
        )
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.branch),
        title = stringResource(Res.string.rebase_worktree_dialog_title, branchName, baseName),
        subtitle = subtitle,
        primaryActionText = stringResource(Res.string.rebase_worktree_dialog_rebase),
        isPrimaryActionEnabled = state.canConfirm,
        onDismiss = onDismiss,
        onPrimaryActionClicked = onConfirm,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps long messages on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state.error

            when {
                error != null -> DialogWarning(error.getStyledErrorText())
                rebase == null -> DialogNote(stringResource(Res.string.worktree_dialog_checking))
                else -> RebaseDetails(rebase, state, branchName, baseName)
            }
        }
    }
}

@Composable
private fun RebaseDetails(
    rebase: WorktreeRebase,
    state: RebaseWorktreeState,
    branchName: String,
    baseName: String,
) {
    val refusal = state.refusal

    // A rebase that can't run says only why: what it would do doesn't matter then
    if (refusal != null) {
        DialogWarning(refusal.text(rebase.worktree.name, branchName, baseName))
        return
    }

    RecentChangeNotice(rebase.worktree.name, rebase.lastChange)
    WorktreeCommands(state.steps)
}

@Composable
private fun WorktreeRebaseRefusal.text(worktreeName: String, branchName: String, baseName: String): String =
    when (this) {
        WorktreeRebaseRefusal.UpToDate ->
            stringResource(Res.string.rebase_worktree_refusal_up_to_date, branchName, baseName)

        WorktreeRebaseRefusal.WorktreeMissing ->
            stringResource(Res.string.rebase_worktree_refusal_missing, worktreeName)

        WorktreeRebaseRefusal.StatusUnknown ->
            stringResource(Res.string.rebase_worktree_refusal_status_unknown, worktreeName)

        is WorktreeRebaseRefusal.HasChanges ->
            pluralStringResource(Res.plurals.rebase_worktree_refusal_changes, changes, worktreeName, changes)
    }
