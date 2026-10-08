package dev.app.leaf.ui.dialogs.base

import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.outlinedTextFieldColors
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Asks for a user name and a password, or only for the part that git doesn't know, as git does:
 * - with a [user] name that git already knows, it shows it and asks only for the password, and [onAccept] gets [user];
 * - without [askPassword], a credential helper gave the password, so it asks only for the user name, and [onAccept]
 *   gets an empty password.
 */
@Composable
fun UserPasswordDialog(
    title: String,
    subtitle: String,
    icon: Painter,
    user: String? = null,
    askPassword: Boolean = true,
    onDismiss: () -> Unit,
    onAccept: (user: String, password: String) -> Unit,
) {
    var userField by remember { mutableStateOf(user.orEmpty()) }
    var passwordField by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val userFieldFocusRequester = remember { FocusRequester() }
    val passwordFieldFocusRequester = remember { FocusRequester() }
    val actionsFocusRequester = remember { FocusRequester() }
    val acceptDialog = {
        onAccept(user ?: userField, passwordField)
    }
    // The first field that the user can edit
    val firstFieldFocusRequester = if (user == null) userFieldFocusRequester else passwordFieldFocusRequester

    IconBasedDialog(
        icon = icon,
        title = title,
        subtitle = subtitle,
        primaryActionText = stringResource(Res.string.generic_button_continue),
        onDismiss = onDismiss,
        onPrimaryActionClicked = acceptDialog,
        beforeActionsFocusRequester = if (askPassword) passwordFieldFocusRequester else userFieldFocusRequester,
        actionsFocusRequester = actionsFocusRequester,
        afterActionsFocusRequester = firstFieldFocusRequester,
    ) {
        // A known user name is shown but can't be changed: git sends it to the credential helpers
        AdjustableOutlinedTextField(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .focusRequester(userFieldFocusRequester)
                .focusProperties {
                    if (askPassword) {
                        this.next = passwordFieldFocusRequester
                    }
                }
                .width(300.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.matchesBinding(KeybindingOption.SIMPLE_ACCEPT)) {
                        if (askPassword) {
                            passwordFieldFocusRequester.requestFocus()
                        } else {
                            acceptDialog()
                        }
                        true
                    } else {
                        false
                    }
                },
            value = userField,
            enabled = user == null,
            textStyle = LocalTextStyle.current.copy(
                fontSize = MaterialTheme.typography.body1.fontSize,
                color = if (user == null) {
                    MaterialTheme.colors.onBackground
                } else {
                    MaterialTheme.colors.onBackgroundSecondary
                },
            ),
            colors = outlinedTextFieldColors(),
            maxLines = 1,
            singleLine = true,
            hint = "Username",
            onValueChange = {
                userField = it
            },
        )

        // Not asked for when a credential helper gave it
        if (askPassword) {
            AdjustableOutlinedTextField(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .focusRequester(passwordFieldFocusRequester)
                    .focusProperties {
                        if (user == null) {
                            this.previous = userFieldFocusRequester
                        }
                        this.next = actionsFocusRequester
                    }
                    .width(300.dp)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.matchesBinding(KeybindingOption.SIMPLE_ACCEPT)) {
                            acceptDialog()
                            true
                        } else {
                            false
                        }
                    },
                value = passwordField,
                maxLines = 1,
                singleLine = true,
                colors = outlinedTextFieldColors(),
                hint = "Password",
                onValueChange = {
                    passwordField = it
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val visibilityIcon = if (showPassword) {
                        Res.drawable.visibility_off
                    } else {
                        Res.drawable.visibility
                    }

                    IconButton(
                        onClick = {
                            showPassword = !showPassword
                            passwordFieldFocusRequester.requestFocus()
                        },
                        modifier = Modifier.handOnHover()
                            .size(20.dp),
                    ) {
                        Icon(
                            painterResource(visibilityIcon),
                            contentDescription = null,
                            tint = MaterialTheme.colors.onBackground,
                        )
                    }
                }
            )
        }

        LaunchedEffect(Unit) {
            firstFieldFocusRequester.requestFocus()
        }
    }
}