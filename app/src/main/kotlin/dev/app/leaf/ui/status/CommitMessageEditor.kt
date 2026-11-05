// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import dev.app.leaf.ui.dialogs.ConfirmableAction

/**
 * The commit message in the centre pane, in the log's place, with a file's diff under it, while the changes pane keeps
 * the lists and the buttons (docs/fork/window-chrome.md, section 6). It takes the focus, with the caret where it was.
 */
@Composable
fun CommitMessageEditor(
    statusState: StatusState,
    onAction: (StatusAction) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    StatusCommitField(
        statusState = statusState,
        onAction = onAction,
        onConfirmAction = onConfirmAction,
        part = CommitFieldPart.MESSAGE,
        modifier = modifier.fillMaxSize(),
        focusRequester = focusRequester,
    )
}
