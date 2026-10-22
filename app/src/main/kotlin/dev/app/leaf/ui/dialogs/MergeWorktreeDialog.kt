// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeMerge
import dev.app.leaf.domain.models.WorktreeMergeKind
import dev.app.leaf.domain.models.WorktreeMergeRefusal
import dev.app.leaf.domain.worktrees.name
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.baseBranchName
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 440.dp

/**
 * Confirms squash-merging or merging a worktree's branch into the base's local branch (fork-only). It says what the
 * merge leaves out and what may stop it, shows the squash commit's message, and the commands that run.
 */
@Composable
fun MergeWorktreeDialog(
    viewModel: MergeWorktreeViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val merge = state.merge
    val squash = viewModel.kind == WorktreeMergeKind.SQUASH
    val targetName = baseBranchName(merge?.target ?: viewModel.target)
    val branchName = baseBranchName(merge?.branch ?: viewModel.worktree.branch.orEmpty())

    val subtitle = when {
        merge == null -> stringResource(Res.string.merge_worktree_dialog_subtitle_loading, branchName, targetName)
        squash -> pluralStringResource(
            Res.plurals.merge_worktree_dialog_squash_subtitle,
            merge.commitCount,
            branchName,
            merge.commitCount,
            targetName,
        )

        else -> pluralStringResource(
            Res.plurals.merge_worktree_dialog_merge_subtitle,
            merge.commitCount,
            branchName,
            merge.commitCount,
            targetName,
        )
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.merge),
        title = if (squash) {
            stringResource(Res.string.merge_worktree_dialog_squash_title, targetName)
        } else {
            stringResource(Res.string.merge_worktree_dialog_merge_title, targetName)
        },
        subtitle = subtitle,
        primaryActionText = if (squash) {
            stringResource(Res.string.merge_worktree_dialog_squash)
        } else {
            stringResource(Res.string.merge_worktree_dialog_merge)
        },
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
                merge == null -> DialogNote(stringResource(Res.string.worktree_dialog_checking))
                else -> MergeDetails(merge, state, branchName, targetName)
            }
        }
    }
}

@Composable
private fun MergeDetails(
    merge: WorktreeMerge,
    state: MergeWorktreeState,
    branchName: String,
    targetName: String,
) {
    val refusal = state.refusal

    // A merge that can't run says only why: what it would do doesn't matter then
    if (refusal != null) {
        DialogWarning(refusal.text(branchName, targetName))
        return
    }

    val worktreeName = merge.worktree.name

    RecentChangeNotice(worktreeName, merge.lastChange)

    val branchStatus = merge.branchStatus

    if (branchStatus?.isDirty == true) {
        val changes = branchStatus.staged + branchStatus.unstaged + branchStatus.untracked + branchStatus.conflicted

        DialogNotice(
            pluralStringResource(Res.plurals.merge_worktree_dialog_branch_changes, changes, worktreeName, changes)
        )
    }

    val targetWorktree = merge.targetWorktree
    val targetStatus = merge.targetStatus

    if (targetWorktree == null) {
        DialogNote(
            stringResource(Res.string.merge_worktree_dialog_check_out_first, targetName, merge.tabWorktree.name)
        )
    } else if (targetStatus != null && targetStatus.unstaged + targetStatus.untracked > 0) {
        val changes = targetStatus.unstaged + targetStatus.untracked

        DialogNote(
            pluralStringResource(
                Res.plurals.merge_worktree_dialog_target_changes,
                changes,
                targetWorktree.name,
                targetName,
                changes,
            )
        )
    }

    val message = state.squashMessage

    if (message != null) {
        SquashMessage(message)
    }

    WorktreeCommands(state.steps)
}

/** The squash commit's message, which the commit field shows afterwards, where the user can still edit it. */
@Composable
internal fun SquashMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.merge_worktree_dialog_message),
            color = MaterialTheme.colors.onBackgroundSecondary,
            style = MaterialTheme.typography.caption,
        )

        SelectionContainer {
            Text(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colors.secondarySurface)
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                maxLines = MAX_MESSAGE_LINES,
            )
        }
    }
}

/** A long branch's message is cut here in the dialog, which must fit the window. The commit field has all of it. */
private const val MAX_MESSAGE_LINES = 12

@Composable
private fun WorktreeMergeRefusal.text(branchName: String, targetName: String): String = when (this) {
    WorktreeMergeRefusal.NothingToMerge ->
        stringResource(Res.string.merge_worktree_refusal_nothing, branchName, targetName)

    WorktreeMergeRefusal.AlreadyInTarget ->
        stringResource(Res.string.merge_worktree_refusal_already_in_target, branchName, targetName)

    is WorktreeMergeRefusal.TargetHasChanges ->
        stringResource(Res.string.merge_worktree_refusal_target_changes, worktree.name, targetName)

    is WorktreeMergeRefusal.TargetStatusUnknown ->
        stringResource(Res.string.merge_worktree_refusal_target_status_unknown, worktree.name, targetName)

    is WorktreeMergeRefusal.TargetWorktreeMissing ->
        stringResource(Res.string.merge_worktree_refusal_target_missing, worktree.name, targetName)

    is WorktreeMergeRefusal.TargetInUse -> when (use) {
        WorktreeBranchUse.Bisecting ->
            stringResource(Res.string.merge_worktree_refusal_target_bisecting, worktree.name, targetName)

        else -> stringResource(Res.string.merge_worktree_refusal_target_rebasing, worktree.name, targetName)
    }

    WorktreeMergeRefusal.TabWorktreeIsBranchWorktree ->
        stringResource(Res.string.merge_worktree_refusal_tab_is_branch_worktree, targetName, branchName)

    is WorktreeMergeRefusal.TabWorktreeHasChanges ->
        stringResource(Res.string.merge_worktree_refusal_tab_changes, targetName, worktree.name)
}
