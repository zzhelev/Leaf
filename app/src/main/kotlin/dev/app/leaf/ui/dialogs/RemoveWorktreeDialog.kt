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
import dev.app.leaf.domain.models.WorktreeRemoval
import dev.app.leaf.domain.worktrees.name
import dev.app.leaf.ui.baseBranchName
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val CONTENT_WIDTH = 440.dp

/** A worktree that changed this recently may have an agent working in it. */
private val RECENT_CHANGE = 5.minutes

/**
 * Confirms removing a worktree (fork-only), or discarding it: removing it with its uncommitted changes and deleting its
 * branch. It says what would be lost, and shows the commands that run.
 */
@Composable
fun RemoveWorktreeDialog(
    viewModel: RemoveWorktreeViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val removal = state.removal
    val discard = viewModel.discard
    val worktreeName = viewModel.worktree.name

    val subtitle = when {
        !discard -> stringResource(Res.string.remove_worktree_dialog_subtitle, worktreeName)
        removal?.branch != null ->
            stringResource(Res.string.discard_worktree_dialog_subtitle, worktreeName, removal.branch.orEmpty())

        else -> stringResource(Res.string.discard_worktree_dialog_subtitle_no_branch, worktreeName)
    }

    val primaryActionText = when {
        !discard -> stringResource(Res.string.remove_worktree_dialog_remove)
        state.confirmingLoss -> stringResource(Res.string.discard_worktree_dialog_discard_anyway)
        else -> stringResource(Res.string.discard_worktree_dialog_discard)
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.delete),
        title = if (discard) {
            stringResource(Res.string.discard_worktree_dialog_title)
        } else {
            stringResource(Res.string.remove_worktree_dialog_title)
        },
        subtitle = subtitle,
        primaryActionText = primaryActionText,
        isPrimaryActionEnabled = state.canConfirm,
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            if (viewModel.confirm()) {
                onDismiss()
            }
        },
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
                removal == null -> DialogNote(stringResource(Res.string.worktree_dialog_checking))
                else -> RemovalDetails(
                    removal = removal,
                    state = state,
                    discard = discard,
                    onForceChanged = viewModel::setForce,
                    onOverrideLockChanged = viewModel::setOverrideLock,
                    onDeleteBranchChanged = viewModel::setDeleteBranch,
                )
            }
        }
    }
}

@Composable
private fun RemovalDetails(
    removal: WorktreeRemoval,
    state: RemoveWorktreeState,
    discard: Boolean,
    onForceChanged: (Boolean) -> Unit,
    onOverrideLockChanged: (Boolean) -> Unit,
    onDeleteBranchChanged: (Boolean) -> Unit,
) {
    val options = state.options
    val worktreeName = removal.worktree.name
    val lastChange = removal.lastChange

    if (lastChange != null) {
        val sinceChange = (System.currentTimeMillis() - lastChange).coerceAtLeast(0).milliseconds

        if (sinceChange < RECENT_CHANGE) {
            val minutes = sinceChange.inWholeMinutes.toInt()

            DialogNotice(
                if (minutes == 0) {
                    stringResource(Res.string.worktree_dialog_recent_change_now, worktreeName)
                } else {
                    pluralStringResource(Res.plurals.worktree_dialog_recent_change, minutes, worktreeName, minutes)
                }
            )
        }
    }

    val locked = removal.worktree.locked

    if (locked != null) {
        DialogNotice(
            if (locked.isBlank()) {
                stringResource(Res.string.worktree_dialog_locked, worktreeName)
            } else {
                stringResource(Res.string.worktree_dialog_locked_reason, worktreeName, locked)
            }
        )

        CheckboxText(
            value = options.overrideLock,
            onCheckedChange = { onOverrideLockChanged(!options.overrideLock) },
            text = if (discard) {
                stringResource(Res.string.discard_worktree_dialog_override_lock)
            } else {
                stringResource(Res.string.remove_worktree_dialog_override_lock)
            },
        )
    }

    if (removal.isMissing) {
        DialogNote(stringResource(Res.string.worktree_dialog_folder_missing))
    } else if (removal.hasUncommittedChanges) {
        val changes = removal.status?.let { it.staged + it.unstaged + it.untracked + it.conflicted }

        if (discard) {
            DialogWarning(
                if (changes != null) {
                    pluralStringResource(Res.plurals.discard_worktree_dialog_uncommitted, changes, changes)
                } else {
                    stringResource(Res.string.discard_worktree_dialog_uncommitted_unknown)
                }
            )
        } else {
            CheckboxText(
                value = options.force,
                onCheckedChange = { onForceChanged(!options.force) },
                text = if (changes != null) {
                    pluralStringResource(Res.plurals.remove_worktree_dialog_force, changes, changes)
                } else {
                    stringResource(Res.string.remove_worktree_dialog_force_unknown)
                },
            )
        }
    }

    val branch = removal.branch

    if (branch != null) {
        if (!discard) {
            CheckboxText(
                value = options.deleteBranch,
                onCheckedChange = { onDeleteBranchChanged(!options.deleteBranch) },
                text = stringResource(Res.string.remove_worktree_dialog_delete_branch, branch),
            )
        }

        if (options.deleteBranch) {
            BranchMergedText(removal, branch)
        }
    }

    if (!removal.isMissing) {
        DialogNote(stringResource(Res.string.worktree_dialog_ignored_files))
    }

    WorktreeCommands(state.steps)
}

/** Whether [branch] is merged into the base: a note when it is, a warning when deleting it loses commits. */
@Composable
private fun BranchMergedText(removal: WorktreeRemoval, branch: String) {
    val base = removal.baseBranch?.let { baseBranchName(it) }
    val merged = removal.branchMerged

    when {
        base == null -> DialogWarning(stringResource(Res.string.worktree_dialog_branch_no_base, branch))
        merged == null -> DialogWarning(stringResource(Res.string.worktree_dialog_branch_merge_unknown, branch, base))
        !merged.isMerged -> DialogWarning(
            pluralStringResource(
                Res.plurals.worktree_dialog_branch_not_merged,
                merged.commitsNotInBase,
                branch,
                merged.commitsNotInBase,
                base,
            )
        )

        merged.commitsNotInBase == 0 -> DialogNote(stringResource(Res.string.worktree_dialog_branch_merged, branch, base))
        else -> DialogNote(stringResource(Res.string.worktree_dialog_branch_changes_in_base, branch, base))
    }
}
