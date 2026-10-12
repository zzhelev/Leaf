// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.worktrees.name
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.dialogs.base.SingleTextFieldDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 440.dp

/** Asks for the reason to lock a worktree with (fork-only), and shows the command. */
@Composable
fun LockWorktreeDialog(
    viewModel: LockWorktreeViewModel,
    onDismiss: () -> Unit,
) {
    val mainWorktree by viewModel.mainWorktree.collectAsState()
    var reason by remember { mutableStateOf("") }

    SingleTextFieldDialog(
        icon = painterResource(Res.drawable.lock),
        title = stringResource(Res.string.lock_worktree_dialog_title),
        subtitle = stringResource(Res.string.lock_worktree_dialog_subtitle, viewModel.worktree.name),
        value = reason,
        onValueChange = { reason = it },
        primaryActionText = stringResource(Res.string.lock_worktree_dialog_lock),
        isPrimaryActionEnabled = mainWorktree != null,
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            viewModel.lock(reason)
            onDismiss()
        },
        trailingContent = {
            if (mainWorktree != null) {
                WorktreeCommands(
                    steps = viewModel.steps(reason),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .width(CONTENT_WIDTH),
                )
            }
        },
    )
}

/** Shows what `git worktree prune` would remove (fork-only), and prunes once the user confirms. */
@Composable
fun PruneWorktreesDialog(
    viewModel: PruneWorktreesViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val prunable = state.prunable
    val error = state.error

    IconBasedDialog(
        icon = painterResource(Res.drawable.close),
        title = stringResource(Res.string.prune_worktrees_dialog_title),
        subtitle = stringResource(Res.string.prune_worktrees_dialog_subtitle),
        primaryActionText = stringResource(Res.string.prune_worktrees_dialog_prune),
        isPrimaryActionEnabled = state.canPrune,
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            viewModel.prune()
            onDismiss()
        },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                error != null -> DialogWarning(error.getStyledErrorText())
                prunable == null -> DialogNote(stringResource(Res.string.worktree_dialog_checking))
                prunable.isEmpty() -> DialogNote(stringResource(Res.string.prune_worktrees_dialog_nothing))
                else -> {
                    // git's own words, as `git worktree prune --dry-run -v` says them
                    SelectionContainer {
                        Text(
                            text = prunable.joinToString("\n"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colors.secondarySurface)
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            color = MaterialTheme.colors.onBackground,
                            style = MaterialTheme.typography.body2,
                            fontFamily = monoTypography(),
                        )
                    }

                    WorktreeCommands(state.steps)
                }
            }
        }
    }
}
