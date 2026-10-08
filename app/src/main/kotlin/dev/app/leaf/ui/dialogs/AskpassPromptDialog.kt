// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.dialogs.base.PasswordDialog
import dev.app.leaf.ui.dialogs.base.SingleTextFieldDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * A prompt from git or ssh that Leaf has no dialog of its own for, such as a password for SSH password authentication
 * or a security key's PIN, shown as it is.
 */
@Composable
fun AskpassPromptDialog(
    request: CredentialsRequest.PromptRequest,
    onAnswer: (String) -> Unit,
    onReject: () -> Unit,
) {
    val title = stringResource(Res.string.askpass_prompt_dialog_title)
    val prompt = request.prompt.trim()

    if (request.secret) {
        PasswordDialog(
            title = title,
            subtitle = prompt,
            icon = Res.drawable.lock,
            onDismiss = onReject,
            onAccept = onAnswer,
        )
    } else {
        var answer by remember { mutableStateOf("") }

        SingleTextFieldDialog(
            icon = painterResource(Res.drawable.lock),
            title = title,
            subtitle = prompt,
            value = answer,
            onValueChange = { answer = it },
            primaryActionText = stringResource(Res.string.generic_button_continue),
            isPrimaryActionEnabled = true,
            onDismiss = onReject,
            onPrimaryActionClicked = { onAnswer(answer) },
        )
    }
}

/** A question from ssh that only needs a yes or a no, such as whether to use a key (`SSH_ASKPASS_PROMPT=confirm`). */
@Composable
fun AskpassConfirmDialog(
    request: CredentialsRequest.ConfirmRequest,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    IconBasedDialog(
        icon = painterResource(Res.drawable.lock),
        title = stringResource(Res.string.askpass_confirm_dialog_title),
        subtitle = request.prompt.trim(),
        primaryActionText = stringResource(Res.string.askpass_confirm_dialog_allow),
        onDismiss = onReject,
        onPrimaryActionClicked = onConfirm,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {}
}
