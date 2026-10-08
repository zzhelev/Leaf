package dev.app.leaf.ui.dialogs

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.lock
import dev.app.leaf.ui.dialogs.base.UserPasswordDialog
import org.jetbrains.compose.resources.painterResource

@Composable
fun HttpCredentialsDialog(
    user: String?,
    askPassword: Boolean,
    onDismiss: () -> Unit,
    onAccept: (user: String, password: String) -> Unit,
) {
    UserPasswordDialog(
        title = "Introduce your remote server credentials",
        subtitle = when {
            user != null -> "Your remote requires the password\nof this user"
            !askPassword -> "Your credential helper gave the password.\nEnter the username that goes with it"
            else -> "Your remote requires authentication with a\nusername and a password"
        },
        icon = painterResource(Res.drawable.lock),
        user = user,
        askPassword = askPassword,
        onDismiss = onDismiss,
        onAccept = onAccept,
    )
}