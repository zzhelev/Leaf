// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeBranchUser
import dev.app.leaf.domain.worktrees.nameNextTo
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Widest that a worktree's name gets next to a branch before it's cut. */
private val BADGE_NAME_MAX_WIDTH = 72.dp

/**
 * The worktrees that use each local branch (fork-only), for the badges and menus of the branch list and of the log's
 * branch chips.
 *
 * @param baseBranch the branch that the worktrees are compared to (`refs/heads/main`), null when there's none.
 */
data class BranchWorktreesState(
    val usersByBranch: Map<String, BranchWorktreeUsers> = emptyMap(),
    val baseBranch: String? = null,
) {
    /** The worktrees that use [branch], null for a remote branch or a branch that no worktree uses. */
    fun usersOf(branch: Branch): BranchWorktreeUsers? = if (branch.isLocal) usersByBranch[branch.name] else null
}

/** The worktrees that use each local branch, which the log provides to its branch chips. */
val LocalBranchWorktrees = compositionLocalOf { BranchWorktreesState() }

/**
 * Marks a branch that another worktree uses, with that worktree's name unless it repeats the branch, and a tooltip
 * about the worktree. Shows nothing when only the tab's worktree uses the branch.
 */
@Composable
fun BranchWorktreeBadge(
    branchName: String,
    users: BranchWorktreeUsers,
    baseBranch: String?,
    modifier: Modifier = Modifier,
) {
    val other = users.other ?: return
    val name = other.info.worktree.nameNextTo(branchName)

    DelayedTooltip(branchWorktreeTooltip(other, baseBranch), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BranchWorktreeIcon()

            if (name != null) {
                Text(
                    text = name,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .widthIn(max = BADGE_NAME_MAX_WIDTH),
                )
            }
        }
    }
}

/** A log chip's mark of a branch that another worktree uses, with a tooltip about the worktree. */
@Composable
fun BranchWorktreeChipMark(users: BranchWorktreeUsers, baseBranch: String?, modifier: Modifier = Modifier) {
    val other = users.other ?: return

    DelayedTooltip(branchWorktreeTooltip(other, baseBranch), modifier = modifier) {
        BranchWorktreeIcon()
    }
}

@Composable
private fun BranchWorktreeIcon() {
    Icon(
        painter = painterResource(Res.drawable.folder),
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colors.onBackgroundSecondary,
    )
}

/** What the branch's badge says: how the worktree uses it, what that rules out, then the worktree's own tooltip. */
@Composable
private fun branchWorktreeTooltip(user: WorktreeBranchUser, baseBranch: String?): String {
    val use = when (user.use) {
        WorktreeBranchUse.CheckedOut -> stringResource(Res.string.branch_worktree_tooltip_checked_out)
        WorktreeBranchUse.Rebasing -> stringResource(Res.string.branch_worktree_tooltip_rebasing)
        WorktreeBranchUse.Bisecting -> stringResource(Res.string.branch_worktree_tooltip_bisecting)
    }

    return use + "\n\n" + worktreeTooltip(user.info, baseBranch)
}
