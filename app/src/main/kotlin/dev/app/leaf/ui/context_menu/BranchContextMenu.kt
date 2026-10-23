package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.cherrypick.BranchCherryPickItem
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun branchContextMenuItems(
    branch: Branch,
    isCurrentBranch: Boolean,
    currentBranch: Branch?,
    isLocal: Boolean,
    onCheckoutBranch: (Branch) -> Unit,
    onMergeBranch: (Branch) -> Unit,
    onRebaseBranch: (Branch) -> Unit,
    onDeleteBranch: (Branch) -> Unit,
    onDeleteRemoteBranch: (Branch) -> Unit = {},
    onPushToRemoteBranch: (Branch) -> Unit,
    onPullFromRemoteBranch: (Branch) -> Unit,
    onChangeDefaultUpstreamBranch: (Branch) -> Unit,
    onRenameBranch: (Branch) -> Unit,
    onCopyBranchNameToClipboard: (Branch) -> Unit,
    worktreeUsers: BranchWorktreeUsers? = null,
    worktreesBase: WorktreeBaseBranch? = null,
    onChooseWorktreesBase: (branch: String?) -> Unit = {},
    onSwitchToWorktree: (Worktree) -> Unit = {},
    onNewWorktree: (() -> Unit)? = null,
    // Fork-only: compares the branch with the current branch, which isn't offered on the current branch itself
    onCompareWithCurrent: (() -> Unit)? = null,
    // Fork-only: compares the branch with a branch, tag or commit picked from a list
    onCompareWith: (() -> Unit)? = null,
    // Fork-only: compares the branch with the files on disk
    onCompareWithWorkingTree: (() -> Unit)? = null,
    // Fork-only: the branch's commits that the current branch lacks, and what picking them does
    cherryPick: BranchCherryPickItem? = null,
    onCherryPick: (BranchCherryPickItem) -> Unit = {},
    // Fork-only: squashes the branch's own commits into one
    squashBranch: ContextMenuElement? = null,
): List<ContextMenuElement> {
    // Leaves out what the worktree guards would refuse (fork-only)
    val canCheckout = worktreeUsers?.canCheckout != false
    val canRename = worktreeUsers?.canRename != false
    val canDelete = worktreeUsers?.canDelete != false
    val worktreeToSwitchTo = worktreeUsers?.worktreeToSwitchTo

    return mutableListOf<ContextMenuElement>().apply {
        if (!isCurrentBranch) {
            if (canCheckout) {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_checkout_branch) },
                    icon = { painterResource(Res.drawable.start) },
                    onClick = { onCheckoutBranch(branch) }
                )
            } else if (worktreeToSwitchTo != null) {
                // In place of checking out the branch, which another worktree has (fork-only)
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_switch_to_worktree) },
                    icon = { painterResource(Res.drawable.start) },
                    onClick = { onSwitchToWorktree(worktreeToSwitchTo) },
                )
            }
            if (currentBranch != null && currentBranch.name != "HEAD") {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_merge_branch) },
                    onClick = { onMergeBranch(branch) }
                )
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_rebase_branch) },
                    onClick = { onRebaseBranch(branch) }
                )

                // Fork-only: copies the branch's commits that the current branch lacks
                if (cherryPick != null) {
                    addBranchCherryPick(cherryPick) { onCherryPick(cherryPick) }
                }

                add(ContextMenuElement.ContextSeparator)
            }

        }

        // Fork-only: the current branch too
        if (squashBranch != null) {
            add(squashBranch)
            add(ContextMenuElement.ContextSeparator)
        }

        // Fork-only: the current branch compares with another too
        val compareWithCurrent = onCompareWithCurrent?.takeIf { !isCurrentBranch && currentBranch != null }

        if (compareWithCurrent != null || onCompareWith != null || onCompareWithWorkingTree != null) {
            if (compareWithCurrent != null && currentBranch != null) {
                addCompareWithCurrent(currentBranch, compareWithCurrent)
            }

            if (onCompareWith != null) {
                addCompareWith(onCompareWith)
            }

            if (onCompareWithWorkingTree != null) {
                addCompareWithWorkingTree(onCompareWithWorkingTree)
            }

            add(ContextMenuElement.ContextSeparator)
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
                onClick = { onPushToRemoteBranch(branch) }
            )
            addContextMenu(
                composableLabel = {
                    stringResource(
                        Res.string.branch_context_menu_pull_target_to_current,
                        branch.simpleNameWithRemote,
                        currentBranch.simpleName,
                    )
                },
                onClick = { onPullFromRemoteBranch(branch) },
            )

            add(ContextMenuElement.ContextSeparator)
        }

        if (isLocal) {
            if (canRename) {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.branch_context_menu_rename_branch) },
                    icon = { painterResource(Res.drawable.edit) },
                    onClick = { onRenameBranch(branch) },
                )
            }

            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_change_default_upstream_branch) },
                onClick = { onChangeDefaultUpstreamBranch(branch) }
            )

            add(ContextMenuElement.ContextSeparator)
        }

        if (!isLocal) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_delete_remote_branch) },
                icon = { painterResource(Res.drawable.delete) },
                onClick = { onDeleteRemoteBranch(branch) },
            )

            add(ContextMenuElement.ContextSeparator)
        }

        if (isLocal && !isCurrentBranch && canDelete) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_delete_branch) },
                icon = { painterResource(Res.drawable.delete) },
                onClick = { onDeleteBranch(branch) },
            )

            add(ContextMenuElement.ContextSeparator)
        }

        // The branch that the worktrees are compared to (fork-only): its label, and the base it chooses
        val compareWorktrees = when {
            worktreesBase == null -> null
            branch.name == worktreesBase.chosen ->
                Res.string.branch_context_menu_compare_worktrees_automatically to null

            branch.name != worktreesBase.effective ->
                Res.string.branch_context_menu_compare_worktrees_to_branch to branch.name

            else -> null
        }

        // A worktree that checks the branch out, or starts a branch from it (fork-only)
        if (onNewWorktree != null) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_new_worktree) },
                icon = { painterResource(Res.drawable.folder) },
                onClick = onNewWorktree,
            )
        }

        if (compareWorktrees != null) {
            val (label, base) = compareWorktrees

            addContextMenu(
                composableLabel = { stringResource(label) },
                icon = { painterResource(Res.drawable.compare_arrows) },
                onClick = { onChooseWorktreesBase(base) },
            )
        }

        if (onNewWorktree != null || compareWorktrees != null) {
            add(ContextMenuElement.ContextSeparator)
        }

        addContextMenu(
            composableLabel = { stringResource(Res.string.branch_context_menu_copy_branch_name) },
            icon = { painterResource(Res.drawable.copy) },
            onClick = {
                onCopyBranchNameToClipboard(branch)
            }
        )
    }
}
