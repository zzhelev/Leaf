package dev.app.leaf.ui.dialogs

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.lock
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.ui.dialogs.base.PasswordDialog

@Composable
fun SshPasswordDialog(
    onReject: () -> Unit,
    onAccept: (password: String) -> Unit,
    credentialsRequest: CredentialsRequest.SshCredentialsRequest,
) {
    PasswordDialog(
        title = "Introduce your SSH key's password",
        subtitle = "Your SSH key is protected with a password",
        isRetry = credentialsRequest.isRetry,
        password = credentialsRequest.password,
        retryMessage = "Invalid password, please try again",
        icon = Res.drawable.lock,
        onDismiss = onReject,
        onAccept = onAccept,
    )
}