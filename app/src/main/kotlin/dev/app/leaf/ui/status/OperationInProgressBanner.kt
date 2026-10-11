// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.abortButton
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.dialogs.ConfirmableAction.AbortOperation.Operation
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Dark text on the conflict color, which is the same amber in the light and the dark theme. */
private val OnConflictColor = Color(0xFF1E1E1E)

/** The operation that the repository is in the middle of, and the files that still have conflicts. */
data class OperationInProgress(val operation: Operation, val conflicts: Int)

/**
 * What the banner shows, or null when the repository isn't in the middle of a merge, rebase, cherry-pick or revert.
 * None while the interactive rebase's list is open, which has its own buttons. It follows the repository, so it shows
 * too when git in a terminal or an agent stopped at conflicts, and stays until the operation is finished or aborted.
 */
fun operationInProgress(statusState: StatusState): OperationInProgress? {
    if (statusState.rebaseInteractiveState is RebaseInteractiveState.AwaitingInteraction) {
        return null
    }

    val repositoryState = statusState.repositoryState
    val operation = when {
        repositoryState.isMerging -> Operation.MERGE
        repositoryState.isRebasing -> Operation.REBASE
        repositoryState.isCherryPicking -> Operation.CHERRY_PICK
        repositoryState.isReverting -> Operation.REVERT
        else -> return null
    }

    val conflicts = (statusState.staged + statusState.unstaged)
        .filter { it.statusType == StatusType.CONFLICTING }
        .map { it.filePath }
        .distinct()
        .count()

    return OperationInProgress(operation, conflicts)
}

/**
 * Aborts the operation in progress once the user confirms it, as the status pane's Abort button does: a rebase with
 * `AbortRebase`, the others by resetting the repository state.
 */
fun abortOperationInProgress(
    statusState: StatusState,
    onAction: (StatusAction) -> Unit,
    onConfirmAction: (ConfirmableAction, () -> Unit) -> Unit,
) {
    val repositoryState = statusState.repositoryState
    val operation = when {
        repositoryState.isRebasing -> Operation.REBASE
        repositoryState.isCherryPicking -> Operation.CHERRY_PICK
        repositoryState.isReverting -> Operation.REVERT
        else -> Operation.MERGE
    }

    onConfirmAction(ConfirmableAction.AbortOperation(operation, statusState.changedFilesCount)) {
        onAction(if (operation == Operation.REBASE) StatusAction.AbortRebase else StatusAction.ResetRepositoryState)
        onAction(StatusAction.UpdateCommitMessage(TextFieldValue("")))
    }
}

/**
 * A strip across the repository view while [inProgress] isn't finished (fork-only). The toast that a merge, rebase or
 * pull shows when it stops at conflicts is gone after two seconds; this stays.
 */
@Composable
fun OperationInProgressBanner(
    inProgress: OperationInProgress,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.conflictFile)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.warning),
            contentDescription = null,
            tint = OnConflictColor,
            modifier = Modifier.size(20.dp),
        )

        Text(
            text = inProgress.message(),
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.Medium,
            color = OnConflictColor,
            // Only as wide as the text, so that the button follows it: on a wide window, a button at the far right is
            // too far from what it's about. When the window is narrow, the text wraps and the button stays.
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 8.dp),
        )

        SecondaryButton(
            text = stringResource(inProgress.operation.abortTitle()),
            textColor = MaterialTheme.colors.onError,
            backgroundButton = MaterialTheme.colors.abortButton,
            onClick = onAbort,
        )
    }
}

@Composable
private fun OperationInProgress.message(): String = if (conflicts > 0) {
    val text = when (operation) {
        Operation.MERGE -> Res.plurals.operation_banner_merge_conflicts
        Operation.REBASE -> Res.plurals.operation_banner_rebase_conflicts
        Operation.CHERRY_PICK -> Res.plurals.operation_banner_cherry_pick_conflicts
        Operation.REVERT -> Res.plurals.operation_banner_revert_conflicts
    }

    pluralStringResource(text, conflicts, conflicts)
} else {
    val text = when (operation) {
        Operation.MERGE -> Res.string.operation_banner_merge
        Operation.REBASE -> Res.string.operation_banner_rebase
        Operation.CHERRY_PICK -> Res.string.operation_banner_cherry_pick
        Operation.REVERT -> Res.string.operation_banner_revert
    }

    stringResource(text)
}

private fun Operation.abortTitle() = when (this) {
    Operation.MERGE -> Res.string.abort_dialog_title_merge
    Operation.REBASE -> Res.string.abort_dialog_title_rebase
    Operation.CHERRY_PICK -> Res.string.abort_dialog_title_cherry_pick
    Operation.REVERT -> Res.string.abort_dialog_title_revert
}
