package dev.app.leaf.ui.dialogs

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.lock
import dev.app.leaf.ui.dialogs.base.UserPasswordDialog
import org.jetbrains.compose.resources.painterResource

@Composable
fun HttpCredentialsDialog(
    onDismiss: () -> Unit,
    onAccept: (user: String, password: String) -> Unit,
) {
    UserPasswordDialog(
        title = "Introduce your remote server credentials",
        subtitle = "Your remote requires authentication with a\nusername and a password",
        icon = painterResource(Res.drawable.lock),
        onDismiss = onDismiss,
        onAccept = onAccept,
    )
}