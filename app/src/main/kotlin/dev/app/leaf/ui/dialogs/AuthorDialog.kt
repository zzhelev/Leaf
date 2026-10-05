package dev.app.leaf.ui.dialogs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.warning
import dev.app.leaf.theme.outlinedTextFieldColors
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import dev.app.leaf.viewmodels.AuthorViewModel
import org.jetbrains.compose.resources.painterResource

@Composable
fun AuthorDialog(
    viewModel: AuthorViewModel,
    onDismiss: () -> Unit,
) {
    val authorInfo by viewModel.authorInfo.collectAsState()

    var globalName by remember(authorInfo) { mutableStateOf(authorInfo.globalIdentity.name) }
    var globalEmail by remember(authorInfo) { mutableStateOf(authorInfo.globalIdentity.email) }
    var name by remember(authorInfo) { mutableStateOf(authorInfo.repositoryIdentity.name) }
    var email by remember(authorInfo) { mutableStateOf(authorInfo.repositoryIdentity.email) }

    MaterialDialog(
        onCloseRequested = onDismiss,
        background = MaterialTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp),
        ) {

            Text(
                text = "Global settings",
                color = MaterialTheme.colors.onBackground,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            TextInput(
                title = "Name",
                value = globalName.orEmpty(),
                onValueChange = { globalName = it },
            )
            TextInput(
                title = "Email",
                value = globalEmail.orEmpty(),
                onValueChange = { globalEmail = it },
            )

            Text(
                text = "Repository settings",
                color = MaterialTheme.colors.onBackground,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )

            TextInput(
                title = "Name",
                value = name.orEmpty(),
                onValueChange = { name = it },
            )
            TextInput(
                title = "Email",
                value = email.orEmpty(),
                onValueChange = { email = it },
            )

            val visible = !name.isNullOrBlank() || !email.isNullOrBlank()

            val visibilityAlpha by animateFloatAsState(targetValue = if (visible) 1f else 0f)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(visibilityAlpha)
            ) {
                Icon(
                    painterResource(Res.drawable.warning),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackground,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Repository-level values will override global values",
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 8.dp, start = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .align(Alignment.End)
            ) {
                PrimaryButton(
                    text = "Cancel",
                    modifier = Modifier.padding(end = 8.dp),
                    onClick = onDismiss,
                    backgroundColor = Color.Transparent,
                    textColor = MaterialTheme.colors.onBackground,
                )
                PrimaryButton(
                    onClick = {
                        viewModel.saveAuthorInfo(
                            globalName,
                            globalEmail,
                            name,
                            email,
                        )
                        onDismiss()
                    },
                    text = "Save data"
                )
            }
        }
    }
}


@Composable
private fun TextInput(
    title: String,
    value: String,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .width(400.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.body1,
            modifier = Modifier
                .width(80.dp)
                .padding(end = 16.dp),
        )

        AdjustableOutlinedTextField(
            value = value,
            modifier = Modifier
                .weight(1f),
            enabled = enabled,
            onValueChange = onValueChange,
            colors = outlinedTextFieldColors(),
            singleLine = true,
        )
    }
}