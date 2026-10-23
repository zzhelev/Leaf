// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.branches.remoteAndName
import dev.app.leaf.domain.errors.DeleteBranchError
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.amberText
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 360.dp

@Composable
fun DeleteBranchDialog(
    viewModel: DeleteBranchViewModel,
    onDismiss: () -> Unit,
) {
    DeleteRefDialog(
        viewModel = viewModel,
        title = stringResource(Res.string.delete_branch_dialog_title),
        subtitle = stringResource(Res.string.delete_branch_dialog_subtitle, viewModel.branch.simpleName),
        onDismiss = onDismiss,
    )
}

@Composable
fun DeleteTagDialog(
    viewModel: DeleteTagViewModel,
    onDismiss: () -> Unit,
) {
    DeleteRefDialog(
        viewModel = viewModel,
        title = stringResource(Res.string.delete_tag_dialog_title),
        subtitle = stringResource(Res.string.delete_tag_dialog_subtitle, viewModel.tag.simpleName),
        onDismiss = onDismiss,
    )
}

/**
 * Confirms deleting a ref. When git refuses, says why, and the primary action becomes "Delete anyway". When a worktree
 * uses the branch, it says so and the primary action is disabled.
 */
@Composable
private fun DeleteRefDialog(
    viewModel: DeleteRefViewModel,
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) {
            onDismiss()
        }
    }

    DeleteRefDialogContent(
        state = state,
        title = title,
        subtitle = subtitle,
        onDeletesCopyChange = viewModel::setDeletesCopy,
        onDelete = viewModel::delete,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun DeleteRefDialogContent(
    state: DeleteRefState,
    title: String,
    subtitle: String,
    onDeletesCopyChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val refusal = state.refusal
    val error = state.error
    val copy = state.remoteCopy?.takeIf { state.deletesCopy }

    IconBasedDialog(
        icon = painterResource(Res.drawable.delete),
        title = title,
        subtitle = subtitle,
        primaryActionText = when {
            refusal != null -> stringResource(Res.string.delete_ref_dialog_delete_anyway)
            copy != null -> stringResource(Res.string.delete_branch_dialog_delete_with_copy, copy.remote)
            else -> stringResource(Res.string.delete_ref_dialog_delete)
        },
        // A worktree uses the branch, which force doesn't get past either, so another try can't delete it
        isPrimaryActionEnabled = !state.isDeleting && !state.isDeleted && error !is DeleteBranchError,
        onDismiss = onDismiss,
        onPrimaryActionClicked = onDelete,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
        // Deleting on a server affects everyone, as in the remote's "Delete branches" dialog
        primaryActionColors = if (copy != null) MaterialTheme.styleColors.destructive else null,
    ) {
        // The dialog is as wide as its content, so this keeps long messages on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.remoteCopy?.let { offered ->
                CheckboxText(
                    value = state.deletesCopy,
                    onCheckedChange = { if (!state.isDeleting) onDeletesCopyChange(!state.deletesCopy) },
                    text = stringResource(
                        Res.string.delete_branch_dialog_also_copy,
                        offered.info.branch.remoteAndName,
                        offered.remote,
                    ),
                )

                if (offered.isRisky) {
                    Text(
                        text = copyRisk(offered).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.amberText,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }

            if (refusal != null) {
                DialogWarning(refusal.refusalText())
            }

            if (error != null) {
                DialogWarning(error.getStyledErrorText())
            }
        }
    }
}

@Composable
private fun DeleteRefError.refusalText(): String {
    val commitsText = if (commitsOnlyOnRef > 0) {
        pluralStringResource(Res.plurals.delete_ref_dialog_commits_lost, commitsOnlyOnRef, commitsOnlyOnRef)
    } else {
        stringResource(Res.string.delete_ref_dialog_no_commits_lost)
    }

    return when (this) {
        is DeleteRefError.BranchNotMerged ->
            stringResource(Res.string.delete_branch_dialog_not_merged, branchName) + " " + commitsText

        is DeleteRefError.TagHasOwnCommits -> commitsText
    }
}
