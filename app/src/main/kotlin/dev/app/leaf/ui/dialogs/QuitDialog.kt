// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.merge
import dev.app.leaf.app.generated.resources.quit_dialog_discard
import dev.app.leaf.app.generated.resources.quit_dialog_save
import dev.app.leaf.app.generated.resources.quit_dialog_subtitle
import dev.app.leaf.app.generated.resources.quit_dialog_title
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.QuitPrompt
import dev.app.leaf.ui.UnsavedResolverFile
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 400.dp

/**
 * Asks before Leaf quits while a conflict resolver has changes that aren't saved (fork-only): [onAnswer] gets true to
 * save them and quit, false to quit without them, or null to stay (Cancel, Esc). Enter saves and quits.
 */
@Composable
fun QuitDialog(prompt: QuitPrompt, onAnswer: (save: Boolean?) -> Unit) {
    val count = prompt.files.size

    // Around the dialog, so that Enter reaches it from the dialog's elements, unless a button that has the focus takes it
    Box(
        modifier = Modifier.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.matchesBinding(KeybindingOption.SIMPLE_ACCEPT)) {
                onAnswer(true)
                true
            } else {
                false
            }
        },
    ) {
        IconBasedDialog(
            icon = painterResource(Res.drawable.merge),
            title = stringResource(Res.string.quit_dialog_title),
            subtitle = pluralStringResource(Res.plurals.quit_dialog_subtitle, count, count),
            primaryActionText = stringResource(Res.string.quit_dialog_save),
            secondaryActionText = stringResource(Res.string.quit_dialog_discard),
            onSecondaryActionClicked = { onAnswer(false) },
            beforeActionsFocusRequester = null,
            actionsFocusRequester = null,
            afterActionsFocusRequester = null,
            onDismiss = { onAnswer(null) },
            onPrimaryActionClicked = { onAnswer(true) },
        ) {
            // The dialog is as wide as its content, so this keeps the message on fewer lines
            Column(
                modifier = Modifier
                    .width(CONTENT_WIDTH)
                    .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(vertical = 4.dp),
            ) {
                prompt.files.forEach { file -> UnsavedFileRow(file) }
            }
        }
    }
}

/** The file, and the tab that has it, named as the tab is. */
@Composable
private fun UnsavedFileRow(file: UnsavedResolverFile) {
    val tabName = file.tabName.collectAsState().value
    val worktreeName = file.worktreeName.collectAsState().value
    val tab = listOfNotNull(tabName, worktreeName).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = file.path,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )

        if (tab.isNotEmpty()) {
            Text(
                text = tab,
                color = MaterialTheme.colors.onBackgroundSecondary,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
