// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getErrorText
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

/** Confirms deleting a ref. When git refuses, says why, and the primary action becomes "Delete anyway". */
@Composable
private fun DeleteRefDialog(
    viewModel: DeleteRefViewModel,
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val refusal = state.refusal
    val error = state.error

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) {
            onDismiss()
        }
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.delete),
        title = title,
        subtitle = subtitle,
        primaryActionText = if (refusal == null) {
            stringResource(Res.string.delete_ref_dialog_delete)
        } else {
            stringResource(Res.string.delete_ref_dialog_delete_anyway)
        },
        isPrimaryActionEnabled = !state.isDeleting && !state.isDeleted,
        onDismiss = onDismiss,
        onPrimaryActionClicked = { viewModel.delete() },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps long messages on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (refusal != null) {
                DialogWarning(refusal.refusalText())
            }

            if (error != null) {
                DialogWarning(error.getErrorText())
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
