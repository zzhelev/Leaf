// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.sorting.DiscardedFiles
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 360.dp

/** Confirms discarding the unstaged changes of the files in [folderPath], as [discard] says. */
@Composable
fun DiscardChangesDialog(
    viewModel: DiscardChangesViewModel,
    folderPath: String,
    discard: DiscardedFiles,
    onDismiss: () -> Unit,
) {
    val fileCount = discard.entries.size

    IconBasedDialog(
        icon = painterResource(Res.drawable.undo),
        title = stringResource(Res.string.discard_folder_dialog_title),
        subtitle = pluralStringResource(Res.plurals.discard_folder_dialog_subtitle, fileCount, fileCount, folderPath),
        primaryActionText = stringResource(Res.string.discard_folder_dialog_discard),
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            viewModel.discard()
            onDismiss()
        },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps a long folder path on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (discard.keptNewFiles > 0) {
                KeptFilesNote(
                    pluralStringResource(
                        Res.plurals.discard_folder_dialog_new_files_kept,
                        discard.keptNewFiles,
                        discard.keptNewFiles,
                    )
                )
            }

            if (discard.keptConflicted > 0) {
                KeptFilesNote(
                    pluralStringResource(
                        Res.plurals.discard_dialog_conflicted_files_kept,
                        discard.keptConflicted,
                        discard.keptConflicted,
                    )
                )
            }
        }
    }
}

/** Files that discarding leaves as they are. */
@Composable
fun KeptFilesNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colors.onBackgroundSecondary,
        style = MaterialTheme.typography.body2,
        textAlign = TextAlign.Center,
    )
}
