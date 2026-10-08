// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.AppConstants
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.updates.UpdateCheck
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 360.dp

/**
 * Checks for updates and says what it found. An update can be downloaded from here, and a failed check tried again.
 */
@Composable
fun CheckForUpdatesDialog(
    viewModel: CheckForUpdatesViewModel,
    onDismiss: () -> Unit,
) {
    val result by viewModel.result.collectAsState()
    val close = stringResource(Res.string.check_for_updates_dialog_close)

    val subtitle = when (val result = result) {
        null -> stringResource(Res.string.check_for_updates_dialog_checking)
        is UpdateCheck.Available -> stringResource(
            Res.string.check_for_updates_dialog_available,
            result.update.appVersion,
            AppConstants.APP_VERSION,
        )

        UpdateCheck.UpToDate -> stringResource(Res.string.check_for_updates_dialog_up_to_date, AppConstants.APP_VERSION)
        is UpdateCheck.Failed -> stringResource(Res.string.check_for_updates_dialog_failed)
    }

    // Close is the only action until there is something else to do
    val primaryActionText = when (result) {
        is UpdateCheck.Available -> stringResource(Res.string.check_for_updates_dialog_download)
        is UpdateCheck.Failed -> stringResource(Res.string.check_for_updates_dialog_try_again)
        null, UpdateCheck.UpToDate -> close
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.update),
        title = stringResource(Res.string.check_for_updates_dialog_title),
        subtitle = subtitle,
        primaryActionText = primaryActionText,
        showCancelAction = result is UpdateCheck.Available || result is UpdateCheck.Failed,
        cancelActionText = close,
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            when (val result = result) {
                is UpdateCheck.Available -> {
                    viewModel.openDownloadPage(result.update)
                    onDismiss()
                }

                is UpdateCheck.Failed -> viewModel.check()
                null, UpdateCheck.UpToDate -> onDismiss()
            }
        },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps long messages on fewer lines
        Column(modifier = Modifier.width(CONTENT_WIDTH)) {
            val result = result

            if (result is UpdateCheck.Failed) {
                DialogWarning(result.reason)
            }
        }
    }
}
