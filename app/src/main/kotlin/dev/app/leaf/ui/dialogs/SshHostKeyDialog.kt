// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 400.dp

/** Asks whether to trust an SSH server whose host key isn't in known_hosts, as ssh does. */
@Composable
fun SshHostKeyDialog(
    request: CredentialsRequest.SshHostKeyRequest,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    IconBasedDialog(
        icon = painterResource(Res.drawable.lock),
        title = stringResource(Res.string.ssh_host_key_dialog_title),
        subtitle = stringResource(Res.string.ssh_host_key_dialog_subtitle, request.host),
        primaryActionText = stringResource(Res.string.ssh_host_key_dialog_trust),
        onDismiss = onReject,
        onPrimaryActionClicked = onTrust,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        Column(modifier = Modifier.width(CONTENT_WIDTH)) {
            Text(
                text = stringResource(Res.string.ssh_host_key_dialog_fingerprint),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
            )

            SelectionContainer {
                Text(
                    text = request.fingerprint,
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.body2,
                    fontFamily = monoTypography(),
                    color = MaterialTheme.colors.onBackground,
                )
            }

            Text(
                text = stringResource(Res.string.ssh_host_key_dialog_explanation),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
            )
        }
    }
}
