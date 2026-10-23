// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The menu on a commit that is one of [count] selected in the log with Shift (fork-only, steps A2 and A3 of
 * `docs/fork/squash-and-rebase.md`): squashing them ([squash]), copying their hashes, and clearing the selection. It
 * replaces the commit's own menu, whose items are for one commit.
 */
fun selectedCommitsContextMenuItems(
    count: Int,
    onCopyHashes: () -> Unit,
    onClearSelection: () -> Unit,
    squash: ContextMenuElement? = null,
): List<ContextMenuElement> = buildList {
    if (squash != null) {
        add(squash)
        add(ContextMenuElement.ContextSeparator)
    }

    addContextMenu(
        composableLabel = { pluralStringResource(Res.plurals.log_context_menu_copy_selected_hashes, count, count) },
        icon = { painterResource(Res.drawable.copy) },
        onClick = onCopyHashes,
    )

    add(ContextMenuElement.ContextSeparator)

    addContextMenu(
        composableLabel = { stringResource(Res.string.log_selected_commits_clear) },
        onClick = onClearSelection,
    )
}
