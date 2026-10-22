// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.branch_context_menu_cherry_pick_commit
import dev.app.leaf.app.generated.resources.branch_context_menu_cherry_pick_commits
import dev.app.leaf.app.generated.resources.branch_context_menu_cherry_pick_uncounted
import dev.app.leaf.domain.cherrypick.BranchCherryPickItem
import dev.app.leaf.domain.cherrypick.BranchesAhead
import dev.app.leaf.domain.cherrypick.branchCherryPickItem
import dev.app.leaf.domain.models.Branch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the branch menus need for their cherry-pick item (fork-only): how many commits each branch has that the current
 * branch lacks, and what picking does. [onCherryPick] picks a [BranchCherryPickItem.OneCommit] at once, and opens the
 * dialog for [BranchCherryPickItem.Commits].
 */
class BranchCherryPick(
    private val branchesAhead: BranchesAhead,
    private val currentBranch: Branch?,
    val onCherryPick: (Branch, BranchCherryPickItem) -> Unit,
) {
    fun itemFor(branch: Branch): BranchCherryPickItem? =
        branchCherryPickItem(branch, currentBranch, branchesAhead.of(branch))
}

/** The log's branch chips get it through this, which keeps it out of upstream's composables in between. */
val LocalBranchCherryPick = staticCompositionLocalOf<BranchCherryPick?> { null }

/** "Cherry-pick commit a4880d1" for one commit, which runs at once, "Cherry-pick 3 commits…" for several. */
fun MutableList<ContextMenuElement>.addBranchCherryPick(item: BranchCherryPickItem, onClick: () -> Unit) {
    addContextMenu(
        composableLabel = { item.label() },
        onClick = onClick,
    )
}

@Composable
fun BranchCherryPickItem.label(): String = when (this) {
    is BranchCherryPickItem.OneCommit -> stringResource(Res.string.branch_context_menu_cherry_pick_commit, shortHash)
    is BranchCherryPickItem.Commits -> when (val count = count) {
        null -> stringResource(Res.string.branch_context_menu_cherry_pick_uncounted)
        else -> pluralStringResource(Res.plurals.branch_context_menu_cherry_pick_commits, count, count)
    }
}
