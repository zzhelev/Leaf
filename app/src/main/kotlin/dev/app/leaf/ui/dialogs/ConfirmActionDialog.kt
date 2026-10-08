// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 360.dp

/** An action that can lose work, and what its confirmation dialog needs to say about it. */
sealed interface ConfirmableAction {
    data class DeleteSubmodule(val path: String) : ConfirmableAction

    data class DeleteFile(val entry: StatusEntry) : ConfirmableAction

    /** Aborts [operation]. [changedFiles] files have uncommitted changes, which the abort resets. */
    data class AbortOperation(val operation: Operation, val changedFiles: Int) : ConfirmableAction {
        enum class Operation { MERGE, REBASE, CHERRY_PICK, REVERT }
    }

    data object SkipRebaseCommit : ConfirmableAction

    data class DropStash(val stash: Commit) : ConfirmableAction

    data class DeleteRemoteBranch(val branch: Branch) : ConfirmableAction

    data class ForcePush(val branchName: String) : ConfirmableAction

    data class DeleteRemote(val remoteName: String) : ConfirmableAction
}

private class ConfirmationTexts(
    val icon: Painter,
    val title: String,
    val subtitle: String,
    val warning: String?,
    val primaryAction: String,
)

/** Asks before [action]. [onConfirm] runs it. */
@Composable
fun ConfirmActionDialog(
    action: ConfirmableAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val texts = action.texts()

    IconBasedDialog(
        icon = texts.icon,
        title = texts.title,
        subtitle = texts.subtitle,
        primaryActionText = texts.primaryAction,
        onDismiss = onDismiss,
        onPrimaryActionClicked = onConfirm,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps long messages on fewer lines
        Column(modifier = Modifier.width(CONTENT_WIDTH)) {
            if (texts.warning != null) {
                DialogWarning(texts.warning)
            }
        }
    }
}

/** A message about work that the action loses. */
@Composable
fun DialogWarning(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.error)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        color = MaterialTheme.colors.onError,
        style = MaterialTheme.typography.body2,
    )
}

@Composable
private fun ConfirmableAction.texts(): ConfirmationTexts {
    val delete = painterResource(Res.drawable.delete)
    val warning = painterResource(Res.drawable.warning)

    return when (this) {
        is ConfirmableAction.DeleteSubmodule -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_submodule_dialog_title),
            subtitle = stringResource(Res.string.delete_submodule_dialog_subtitle, path),
            warning = stringResource(Res.string.delete_submodule_dialog_warning),
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.DeleteFile -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_file_dialog_title),
            subtitle = stringResource(Res.string.delete_file_dialog_subtitle, entry.filePath),
            // Unstaged ADDED entries are the untracked files
            warning = if (entry.statusType == StatusType.ADDED) {
                stringResource(Res.string.delete_file_dialog_warning_untracked)
            } else {
                stringResource(Res.string.delete_file_dialog_warning_tracked)
            },
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.AbortOperation -> {
            val (title, subtitle) = when (operation) {
                ConfirmableAction.AbortOperation.Operation.MERGE ->
                    Res.string.abort_dialog_title_merge to Res.string.abort_dialog_subtitle_merge

                ConfirmableAction.AbortOperation.Operation.REBASE ->
                    Res.string.abort_dialog_title_rebase to Res.string.abort_dialog_subtitle_rebase

                ConfirmableAction.AbortOperation.Operation.CHERRY_PICK ->
                    Res.string.abort_dialog_title_cherry_pick to Res.string.abort_dialog_subtitle_cherry_pick

                ConfirmableAction.AbortOperation.Operation.REVERT ->
                    Res.string.abort_dialog_title_revert to Res.string.abort_dialog_subtitle_revert
            }

            ConfirmationTexts(
                icon = warning,
                title = stringResource(title),
                subtitle = stringResource(subtitle),
                warning = if (changedFiles > 0) {
                    pluralStringResource(Res.plurals.abort_dialog_changes_discarded, changedFiles, changedFiles)
                } else {
                    null
                },
                primaryAction = stringResource(Res.string.abort_dialog_abort),
            )
        }

        ConfirmableAction.SkipRebaseCommit -> ConfirmationTexts(
            icon = warning,
            title = stringResource(Res.string.skip_rebase_dialog_title),
            subtitle = stringResource(Res.string.skip_rebase_dialog_subtitle),
            warning = null,
            primaryAction = stringResource(Res.string.skip_rebase_dialog_skip),
        )

        is ConfirmableAction.DropStash -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.drop_stash_dialog_title),
            subtitle = stringResource(Res.string.drop_stash_dialog_subtitle, stash.shortMessage),
            warning = stringResource(Res.string.drop_stash_dialog_warning),
            primaryAction = stringResource(Res.string.drop_stash_dialog_drop),
        )

        is ConfirmableAction.DeleteRemoteBranch -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_remote_branch_dialog_title),
            subtitle = stringResource(
                Res.string.delete_remote_branch_dialog_subtitle,
                branch.simpleName,
                branch.remoteName,
            ),
            warning = stringResource(Res.string.delete_remote_branch_dialog_warning),
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.ForcePush -> ConfirmationTexts(
            icon = warning,
            title = stringResource(Res.string.force_push_dialog_title),
            subtitle = stringResource(Res.string.force_push_dialog_subtitle, branchName),
            warning = stringResource(Res.string.force_push_dialog_warning, branchName),
            primaryAction = stringResource(Res.string.force_push_dialog_push),
        )

        is ConfirmableAction.DeleteRemote -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_remote_dialog_title),
            subtitle = stringResource(Res.string.delete_remote_dialog_subtitle, remoteName),
            warning = null,
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )
    }
}
