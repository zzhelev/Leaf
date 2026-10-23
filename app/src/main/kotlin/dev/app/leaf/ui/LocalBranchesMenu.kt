// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.ui.components.sort.SortMenuButton
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.context_menu.ContextMenuElement
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Local branches section's own menu (⋯), next to its sort button, as the Worktrees section has one: what acts on
 * the section's branches, which the sort button's menu, about how they're listed, doesn't hold (upstream issue 116).
 */
@Composable
internal fun LocalBranchesMenuButton(onDeleteMergedBranches: () -> Unit) {
    SortMenuButton(
        isActive = false,
        activeLabel = null,
        showFolderIcon = false,
        menuItems = listOf(
            SortMenuItem.Option(
                label = stringResource(Res.string.side_pane_local_branches_delete_merged),
                hint = stringResource(Res.string.side_pane_local_branches_delete_merged_hint),
                isChecked = false,
                closesMenu = true,
                icon = Res.drawable.delete,
                onClick = onDeleteMergedBranches,
            ),
        ),
        icon = Res.drawable.more_horiz,
        tooltip = stringResource(Res.string.side_pane_local_branches_menu_tooltip),
    )
}

/** The same actions as [LocalBranchesMenuButton], on a right-click of the section's header. */
internal fun localBranchesContextMenu(onDeleteMergedBranches: () -> Unit): List<ContextMenuElement> = listOf(
    ContextMenuElement.ContextTextEntry(
        composableLabel = { stringResource(Res.string.side_pane_local_branches_delete_merged) },
        icon = { painterResource(Res.drawable.delete) },
        onClick = onDeleteMergedBranches,
    ),
)
