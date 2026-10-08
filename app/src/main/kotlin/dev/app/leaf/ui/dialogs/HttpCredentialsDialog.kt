package dev.app.leaf.ui.dialogs

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.lock
import dev.app.leaf.ui.dialogs.base.UserPasswordDialog
import org.jetbrains.compose.resources.painterResource

@Composable
fun HttpCredentialsDialog(
    user: String?,
    onDismiss: () -> Unit,
    onAccept: (user: String, password: String) -> Unit,
) {
    UserPasswordDialog(
        title = "Introduce your remote server credentials",
        subtitle = if (user == null) {
            "Your remote requires authentication with a\nusername and a password"
        } else {
            "Your remote requires the password\nof this user"
        },
        icon = painterResource(Res.drawable.lock),
        user = user,
        onDismiss = onDismiss,
        onAccept = onAccept,
    )
}