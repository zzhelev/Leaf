// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.WorktreeBaseBranch
import org.eclipse.jgit.lib.Constants
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The menu items of the remote branches that share a local branch's chip in the log (fork-only), which follow the
 * local branch's items. They're on the same commit as the local branch, so checking one out, merging or rebasing it
 * would do what the local branch's items do, and are left out. What's left names the remote branch, as the local
 * branch's items are just above.
 */
fun sharedChipRemoteItems(
    remotes: List<Branch>,
    currentBranch: Branch?,
    onPushToRemoteBranch: (Branch) -> Unit,
    onPullFromRemoteBranch: (Branch) -> Unit,
    onDeleteRemoteBranch: (Branch) -> Unit,
    onCopyBranchNameToClipboard: (Branch) -> Unit,
    worktreesBase: WorktreeBaseBranch?,
    onChooseWorktreesBase: (branch: String?) -> Unit,
    onCompareWithCurrent: ((Branch) -> Unit)? = null,
): List<ContextMenuElement> {
    val current = currentBranch?.takeIf { it.name != Constants.HEAD }

    return buildList<ContextMenuElement> {
        for (remote in remotes) {
            if (isNotEmpty()) add(ContextMenuElement.ContextSeparator)

            val name = remote.simpleNameWithRemote

            if (current != null) {
                addContextMenu(
                    composableLabel = {
                        stringResource(
                            Res.string.branch_context_menu_push_current_to_target,
                            current.simpleName,
                            name,
                        )
                    },
                    onClick = { onPushToRemoteBranch(remote) },
                )
                addContextMenu(
                    composableLabel = {
                        stringResource(
                            Res.string.branch_context_menu_pull_target_to_current,
                            name,
                            current.simpleName,
                        )
                    },
                    onClick = { onPullFromRemoteBranch(remote) },
                )
            }

            if (onCompareWithCurrent != null && currentBranch != null) {
                addCompareWithCurrent(currentBranch, onClick = { onCompareWithCurrent(remote) })
            }

            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_delete_named_remote_branch, name) },
                icon = { painterResource(Res.drawable.delete) },
                onClick = { onDeleteRemoteBranch(remote) },
            )

            when {
                worktreesBase == null -> {}
                remote.name == worktreesBase.chosen -> addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_compare_worktrees_automatically) },
                    icon = { painterResource(Res.drawable.compare_arrows) },
                    onClick = { onChooseWorktreesBase(null) },
                )

                remote.name != worktreesBase.effective -> addContextMenu(
                    composableLabel = {
                        stringResource(Res.string.branch_context_menu_compare_worktrees_to_named_branch, name)
                    },
                    icon = { painterResource(Res.drawable.compare_arrows) },
                    onClick = { onChooseWorktreesBase(remote.name) },
                )
            }

            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_copy_named_branch_name, name) },
                icon = { painterResource(Res.drawable.copy) },
                onClick = { onCopyBranchNameToClipboard(remote) },
            )
        }
    }
}
