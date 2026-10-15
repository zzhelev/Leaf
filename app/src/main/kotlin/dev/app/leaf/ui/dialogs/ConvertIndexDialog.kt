// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.index.UnsupportedIndex
import dev.app.leaf.domain.index.conversionSteps
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 440.dp

/**
 * Shows the commands that convert the worktree's index to the format JGit reads (fork-only), and runs them once the
 * user confirms ([onConvert]), as the worktree dialogs do.
 */
@Composable
fun ConvertIndexDialog(
    index: UnsupportedIndex,
    onConvert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val subtitle = if (index is UnsupportedIndex.Sparse) {
        Res.string.convert_index_dialog_subtitle_sparse
    } else {
        Res.string.convert_index_dialog_subtitle
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.warning),
        title = stringResource(Res.string.convert_index_dialog_title),
        subtitle = stringResource(subtitle),
        primaryActionText = stringResource(Res.string.convert_index_dialog_convert),
        isPrimaryActionEnabled = index.conversionSteps().isNotEmpty(),
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            onConvert()
            onDismiss()
        },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (index is UnsupportedIndex.Split && index.splitIndexConfigured) {
                DialogNote(stringResource(Res.string.convert_index_dialog_split_configured))
            }

            WorktreeCommands(index.conversionSteps())
        }
    }
}
