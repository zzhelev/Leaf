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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.ConflictSides
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

/**
 * The operation that the repository is in the middle of, the files that still have conflicts, and what ours and theirs
 * are while there are some.
 */
data class OperationInProgress(val operation: Operation, val conflicts: Int, val sides: ConflictSides? = null)

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
        statusState.isSquashMergeInProgress -> Operation.SQUASH
        else -> return null
    }

    val conflicts = (statusState.staged + statusState.unstaged)
        .filter { it.statusType == StatusType.CONFLICTING }
        .map { it.filePath }
        .distinct()
        .count()

    return OperationInProgress(operation, conflicts, statusState.conflictSides.takeIf { conflicts > 0 })
}

/**
 * Aborts the operation in progress once the user confirms it, as the status pane's Abort button does: a rebase with
 * `AbortRebase`, a squash merge with `git reset --merge`, the others by resetting the repository state.
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
        !repositoryState.isMerging && statusState.isSquashMergeInProgress -> Operation.SQUASH
        else -> Operation.MERGE
    }

    onConfirmAction(ConfirmableAction.AbortOperation(operation, statusState.changedFilesCount)) {
        onAction(
            when (operation) {
                Operation.REBASE -> StatusAction.AbortRebase
                Operation.SQUASH -> StatusAction.AbortSquashMerge
                else -> StatusAction.ResetRepositoryState
            }
        )
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
    /** Opens the conflicts, while files have them: the built-in resolver or the external merge tool. */
    onResolveConflicts: (() -> Unit)? = null,
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

        val message = inProgress.message()
        // Which branch is ours and which is theirs, as few people remember it
        val sides = inProgress.sides?.summary()

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(message) }

                if (sides != null) {
                    append("   ")
                    append(sides)
                }
            },
            style = MaterialTheme.typography.body1,
            color = OnConflictColor,
            // Only as wide as the text, so that the button follows it: on a wide window, a button at the far right is
            // too far from what it's about. When the window is narrow, the text wraps and the button stays.
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 8.dp),
        )

        if (onResolveConflicts != null && inProgress.conflicts > 0) {
            SecondaryButton(
                text = stringResource(Res.string.operation_banner_resolve_conflicts),
                textColor = Color.White,
                backgroundButton = OnConflictColor,
                onClick = onResolveConflicts,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

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
        Operation.SQUASH -> Res.plurals.operation_banner_squash_conflicts
    }

    pluralStringResource(text, conflicts, conflicts)
} else {
    val text = when (operation) {
        Operation.MERGE -> Res.string.operation_banner_merge
        Operation.REBASE -> Res.string.operation_banner_rebase
        Operation.CHERRY_PICK -> Res.string.operation_banner_cherry_pick
        Operation.REVERT -> Res.string.operation_banner_revert
        Operation.SQUASH -> Res.string.operation_banner_squash
    }

    stringResource(text)
}

private fun Operation.abortTitle() = when (this) {
    Operation.MERGE -> Res.string.abort_dialog_title_merge
    Operation.REBASE -> Res.string.abort_dialog_title_rebase
    Operation.CHERRY_PICK -> Res.string.abort_dialog_title_cherry_pick
    Operation.REVERT -> Res.string.abort_dialog_title_revert
    Operation.SQUASH -> Res.string.abort_dialog_title_squash
}
