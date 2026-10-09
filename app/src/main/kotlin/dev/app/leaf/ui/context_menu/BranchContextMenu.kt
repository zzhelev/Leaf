package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun branchContextMenuItems(
    branch: Branch,
    isCurrentBranch: Boolean,
    currentBranch: Branch?,
    isLocal: Boolean,
    onCheckoutBranch: () -> Unit,
    onMergeBranch: () -> Unit,
    onRebaseBranch: () -> Unit,
    onDeleteBranch: () -> Unit,
    onDeleteRemoteBranch: () -> Unit = {},
    onPushToRemoteBranch: () -> Unit,
    onPullFromRemoteBranch: () -> Unit,
    onChangeDefaultUpstreamBranch: () -> Unit,
    onRenameBranch: () -> Unit,
    onCopyBranchNameToClipboard: () -> Unit,
    worktreeUsers: BranchWorktreeUsers? = null,
): List<ContextMenuElement> {
    // Leaves out what the worktree guards would refuse (fork-only)
    val canCheckout = worktreeUsers?.canCheckout != false
    val canRename = worktreeUsers?.canRename != false
    val canDelete = worktreeUsers?.canDelete != false

    return mutableListOf<ContextMenuElement>().apply {
        if (!isCurrentBranch) {
            if (canCheckout) {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_checkout_branch) },
                    icon = { painterResource(Res.drawable.start) },
                    onClick = onCheckoutBranch
                )
            }
            if (currentBranch != null && currentBranch.name != "HEAD") {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_merge_branch) },
                    onClick = onMergeBranch
                )
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_rebase_branch) },
                    onClick = onRebaseBranch
                )

                add(ContextMenuElement.ContextSeparator)
            }
        }
        if (!isLocal && currentBranch != null && currentBranch.name != "HEAD") { // TODO currentBranch.name != "HEAD" as function or even extension function?
            addContextMenu(
                composableLabel = {
                    stringResource(
                        Res.string.branch_context_menu_push_current_to_target,
                        currentBranch.simpleName,
                        branch.simpleNameWithRemote,
                    )
                },
                onClick = onPushToRemoteBranch
            )
            addContextMenu(
                composableLabel = {
                    stringResource(
                        Res.string.branch_context_menu_pull_target_to_current,
                        branch.simpleNameWithRemote,
                        currentBranch.simpleName,
                    )
                },
                onClick = onPullFromRemoteBranch,
            )

            add(ContextMenuElement.ContextSeparator)
        }

        if (isLocal) {
            if (canRename) {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_rename_branch) },
                    icon = { painterResource(Res.drawable.edit) },
                    onClick = onRenameBranch,
                )
            }

            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_change_default_upstream_branch) },
                onClick = onChangeDefaultUpstreamBranch
            )

            add(ContextMenuElement.ContextSeparator)
        }

        if (!isLocal) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_delete_remote_branch) },
                icon = { painterResource(Res.drawable.delete) },
                onClick = onDeleteRemoteBranch,
            )

            add(ContextMenuElement.ContextSeparator)
        }

        if (isLocal && !isCurrentBranch && canDelete) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_delete_branch) },
                icon = { painterResource(Res.drawable.delete) },
                onClick = onDeleteBranch,
            )

            add(ContextMenuElement.ContextSeparator)
        }

        addContextMenu(
            composableLabel = { stringResource(Res.string.branch_context_menu_copy_branch_name) },
            icon = { painterResource(Res.drawable.copy) },
            onClick = {
                onCopyBranchNameToClipboard()
            }
        )
    }
}
