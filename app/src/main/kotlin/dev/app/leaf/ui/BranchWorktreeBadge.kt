// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeBranchUser
import dev.app.leaf.domain.worktrees.nameNextTo
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Size of the folder icon that marks a branch another worktree uses. */
private val WORKTREE_ICON_SIZE = 14.dp

/** Space between the branch name and the folder icon. */
private val BRANCH_MARK_GAP = 8.dp

/** Space between the folder icon and the worktree's name. */
private val MARK_NAME_GAP = 4.dp

/** Narrowest that a worktree's name gets before it's left out: about five characters. */
private val MIN_WORKTREE_NAME_WIDTH = 44.dp

/** Space at the end of a row without an age or HEAD label after the name. */
private val ROW_END_PADDING = 16.dp

/**
 * The worktrees that use each local branch (fork-only), for the badges and menus of the branch list and of the log's
 * branch chips.
 *
 * @param baseBranch the branch that the worktrees are compared to (`refs/heads/main`), null when there's none.
 * @param baseChoice the base branch and how it was chosen, for the menus that change it, null when the repository has
 * only one worktree.
 */
data class BranchWorktreesState(
    val usersByBranch: Map<String, BranchWorktreeUsers> = emptyMap(),
    val baseBranch: String? = null,
    val baseChoice: WorktreeBaseBranch? = null,
) {
    /** The worktrees that use [branch], null for a remote branch or a branch that no worktree uses. */
    fun usersOf(branch: Branch): BranchWorktreeUsers? = if (branch.isLocal) usersByBranch[branch.name] else null
}

/** The worktrees that use each local branch, which the log provides to its branch chips. */
val LocalBranchWorktrees = compositionLocalOf { BranchWorktreesState() }

/**
 * Chooses the branch that the worktrees are compared to, or goes back to the automatic one when null. The log provides
 * it to its branch chips.
 */
val LocalOnChooseWorktreesBase = staticCompositionLocalOf<(String?) -> Unit> { {} }

/**
 * A local branch's name in the branch list, followed by a folder icon when another worktree uses the branch, and that
 * worktree's name unless it repeats the branch (see [nameNextTo]). The tooltip of the icon and the name is about the
 * worktree.
 *
 * The branch name comes first: it gets all the width it needs, as long as the icon still fits, and ends in "…" when
 * it doesn't. The worktree's name gets what's left, and is left out when that's less than about five characters.
 *
 * @param hasTrailingLabel whether an age or HEAD label follows, which brings its own padding.
 */
@Composable
fun BranchNameWithWorktree(
    text: String,
    fontWeight: FontWeight,
    branchName: String,
    users: BranchWorktreeUsers?,
    baseBranch: String?,
    hasTrailingLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val other = users?.other

    Layout(
        contents = listOf(
            {
                Text(
                    text = text,
                    fontWeight = fontWeight,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            {
                if (other != null) {
                    DelayedTooltip(branchWorktreeTooltip(other, baseBranch)) {
                        WorktreeMark(other.info.worktree.nameNextTo(branchName))
                    }
                }
            },
        ),
        modifier = modifier,
    ) { (branchMeasurables, markMeasurables), constraints ->
        val branchMeasurable = branchMeasurables.single()
        val markMeasurable = markMeasurables.singleOrNull()
        val gap = BRANCH_MARK_GAP.roundToPx()
        val endPadding = if (hasTrailingLabel) 0 else ROW_END_PADDING.roundToPx()
        val available = (constraints.maxWidth - endPadding).coerceAtLeast(0)
        val markIcon = if (markMeasurable != null) gap + WORKTREE_ICON_SIZE.roundToPx() else 0

        val branchFull = branchMeasurable.maxIntrinsicWidth(constraints.maxHeight)
        val branch = branchMeasurable.measure(Constraints(maxWidth = branchNameWidth(available, branchFull, markIcon)))
        val mark = markMeasurable?.measure(Constraints(maxWidth = (available - branch.width - gap).coerceAtLeast(0)))

        val height = maxOf(branch.height, mark?.height ?: 0)

        layout(constraints.maxWidth, height) {
            branch.place(0, (height - branch.height) / 2)
            mark?.place(branch.width + gap, (height - mark.height) / 2)
        }
    }
}

/** The folder icon, followed by the worktree's [name] when it fits (see [worktreeNameWidth]). */
@Composable
private fun WorktreeMark(name: String?) {
    Layout(
        contents = listOf(
            { BranchWorktreeIcon() },
            {
                if (name != null) {
                    Text(
                        text = name,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        ),
    ) { (iconMeasurables, nameMeasurables), constraints ->
        val icon = iconMeasurables.single().measure(Constraints())
        val nameMeasurable = nameMeasurables.singleOrNull()
        val gap = MARK_NAME_GAP.roundToPx()

        val name = nameMeasurable?.let { measurable ->
            val width = worktreeNameWidth(
                space = constraints.maxWidth - icon.width - gap,
                nameFull = measurable.maxIntrinsicWidth(constraints.maxHeight),
                minName = MIN_WORKTREE_NAME_WIDTH.roundToPx(),
            )

            width?.let { measurable.measure(Constraints(maxWidth = it)) }
        }

        val width = icon.width + (name?.let { gap + it.width } ?: 0)
        val height = maxOf(icon.height, name?.height ?: 0)

        layout(width, height) {
            icon.place(0, (height - icon.height) / 2)
            name?.place(icon.width + gap, (height - name.height) / 2)
        }
    }
}

/** The width that a branch's name gets out of [available]: all it needs ([branchFull]), leaving [reserved] free. */
internal fun branchNameWidth(available: Int, branchFull: Int, reserved: Int): Int =
    branchFull.coerceAtMost((available - reserved).coerceAtLeast(0))

/**
 * The width that a worktree's name gets in [space]: all it needs ([nameFull]) when that fits, else [space] down to
 * [minName], where it ends in "…". Null leaves it out.
 */
internal fun worktreeNameWidth(space: Int, nameFull: Int, minName: Int): Int? = when {
    space >= nameFull -> nameFull
    space >= minName -> space
    else -> null
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
        modifier = Modifier.size(WORKTREE_ICON_SIZE),
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
