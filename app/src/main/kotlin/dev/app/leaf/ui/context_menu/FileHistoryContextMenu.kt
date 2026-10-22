// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The menu of a row of file history (fork-only, upstream issues 146 and 149). [onRestoreVersion] is null where the file
 * can't be restored, as in a bare repository, which has no working tree: then there's no item for it.
 * [onCompareWithSelected] compares the file at the row with the file at the selected commit, named [selectedName]
 * (`docs/fork/compare.md`, 7.3); null leaves it out, as on the selected row.
 */
fun fileHistoryContextMenu(
    onRestoreVersion: (() -> Unit)?,
    onCopyCommitHash: () -> Unit,
    onCopyCommitMessage: () -> Unit,
    selectedName: String? = null,
    onCompareWithSelected: (() -> Unit)? = null,
) = mutableListOf<ContextMenuElement>().apply {
    if (onRestoreVersion != null) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.file_history_context_menu_restore_version) },
            icon = { painterResource(Res.drawable.undo) },
            onClick = onRestoreVersion,
        )

        add(ContextMenuElement.ContextSeparator)
    }

    if (selectedName != null && onCompareWithSelected != null) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.compare_with_selected, selectedName) },
            icon = { painterResource(Res.drawable.compare_arrows) },
            onClick = onCompareWithSelected,
            hint = { compareClickName() },
        )

        add(ContextMenuElement.ContextSeparator)
    }

    addContextMenu(
        composableLabel = { stringResource(Res.string.file_history_context_menu_copy_commit_hash) },
        icon = { painterResource(Res.drawable.copy) },
        onClick = onCopyCommitHash,
    )
    addContextMenu(
        composableLabel = { stringResource(Res.string.file_history_context_menu_copy_commit_message) },
        icon = { painterResource(Res.drawable.copy) },
        onClick = onCopyCommitMessage,
    )
}
