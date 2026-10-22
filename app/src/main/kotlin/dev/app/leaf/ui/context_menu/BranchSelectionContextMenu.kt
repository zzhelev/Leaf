// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The menu on a branch that is one of [count] selected (fork-only, upstream issue 116): deleting them, on [remote] when
 * they're its branches, and clearing the selection. It replaces the branch's own menu, whose items are for one branch.
 */
fun selectedBranchesContextMenuItems(
    count: Int,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    remote: String? = null,
): List<ContextMenuElement> = buildList {
    addContextMenu(
        composableLabel = {
            if (remote != null) {
                pluralStringResource(Res.plurals.branch_context_menu_delete_selected_on_remote, count, count, remote)
            } else {
                pluralStringResource(Res.plurals.branch_context_menu_delete_selected, count, count)
            }
        },
        icon = { painterResource(Res.drawable.delete) },
        onClick = onDelete,
    )

    add(ContextMenuElement.ContextSeparator)

    addContextMenu(
        composableLabel = { stringResource(Res.string.side_pane_branches_clear_selection) },
        onClick = onClearSelection,
    )
}

/** The menu on a folder of branches: selecting the [count] branches in it (fork-only, upstream issue 116). */
fun branchFolderContextMenuItems(count: Int, onSelectBranches: () -> Unit): List<ContextMenuElement> = buildList {
    addContextMenu(
        composableLabel = { pluralStringResource(Res.plurals.branch_context_menu_select_folder_branches, count, count) },
        onClick = onSelectBranches,
    )
}
