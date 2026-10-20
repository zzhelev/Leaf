// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The menu of a parent in the commit details (fork-only, upstream issue 270): what a click does, and copying. */
fun parentContextMenu(
    onSelectInLog: () -> Unit,
    onCopyCommitHash: () -> Unit,
) = mutableListOf<ContextMenuElement>().apply {
    addContextMenu(
        composableLabel = { stringResource(Res.string.commit_parents_menu_select) },
        icon = { painterResource(Res.drawable.arrow_upward) },
        onClick = onSelectInLog,
    )
    add(ContextMenuElement.ContextSeparator)
    addContextMenu(
        composableLabel = { stringResource(Res.string.commit_parents_menu_copy_hash) },
        icon = { painterResource(Res.drawable.copy) },
        onClick = onCopyCommitHash,
    )
}
