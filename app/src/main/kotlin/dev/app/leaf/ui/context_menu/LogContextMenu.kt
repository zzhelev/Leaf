package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.app.leaf.app.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun logContextMenu(
    onShowStatusAmending: () -> Unit,
    onCheckoutCommit: () -> Unit,
    onCreateNewBranch: () -> Unit,
    onCreateNewTag: () -> Unit,
    onRevertCommit: () -> Unit,
    onCherryPickCommit: () -> Unit,
    onResetBranch: () -> Unit,
    onCopyCommitHash: () -> Unit,
    showInAmend: Boolean,
    onNewWorktree: (() -> Unit)? = null,
    // Fork-only: comparing the row with the selected commit or the current branch
    compare: CompareMenuItems? = null,
    // Fork-only: rewording the commit, and squashing or fixing it up into its parent
    rewriteItems: List<ContextMenuElement> = emptyList(),
) = mutableListOf<ContextMenuElement>().apply {
    if (showInAmend) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.log_context_menu_show_status_to_amend) },
            icon = { painterResource(Res.drawable.edit) },
            onClick = onShowStatusAmending
        )

        add(ContextMenuElement.ContextSeparator)
    }

    if (rewriteItems.isNotEmpty()) {
        addAll(rewriteItems)
        add(ContextMenuElement.ContextSeparator)
    }

    addContextMenu(
        composableLabel = { stringResource(Res.string.log_context_menu_checkout_commit) },
        icon = { painterResource(Res.drawable.start) },
        onClick = onCheckoutCommit
    )
    addContextMenu(
        composableLabel = { stringResource(Res.string.log_context_menu_create_branch) },
        icon = { painterResource(Res.drawable.branch) },
        onClick = onCreateNewBranch
    )
    if (onNewWorktree != null) {
        // Fork-only
        addContextMenu(
            composableLabel = { stringResource(Res.string.log_context_menu_new_worktree) },
            icon = { painterResource(Res.drawable.folder) },
            onClick = onNewWorktree,
        )
    }
    addContextMenu(
        composableLabel = { stringResource(Res.string.log_context_menu_create_tag) },
        icon = { painterResource(Res.drawable.tag) },
        onClick = onCreateNewTag
    )
    addContextMenu(
        composableLabel = { stringResource(Res.string.log_context_menu_copy_commit_hash) },
        icon = { painterResource(Res.drawable.copy) },
        onClick = onCopyCommitHash
    )

    add(ContextMenuElement.ContextSeparator)

    addCompareItems(compare)

    addContextMenu(
        composableLabel = { stringResource(Res.string.log_context_menu_revert_commit) },
        icon = { painterResource(Res.drawable.revert) },
        onClick = onRevertCommit
    )
    addContextMenu(
        composableLabel = { stringResource(Res.string.log_context_menu_cherry_pick_commit) },
        onClick = onCherryPickCommit
    )
    addContextMenu(
        composableLabel = { stringResource(Res.string.log_context_menu_reset_current_branch_to_commit) },
        icon = { painterResource(Res.drawable.undo) },
        onClick = onResetBranch
    )
}
