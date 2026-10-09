// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.worktrees.WorktreeHeadLabel
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.domain.worktrees.headLabel
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.modifyFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.SideMenuHeader
import dev.app.leaf.ui.components.sort.SortMenuButton
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.addContextMenu
import dev.app.leaf.viewmodels.sidepanel.WorktreesState
import org.jetbrains.compose.resources.painterResource
import org.eclipse.jgit.lib.Repository
import org.jetbrains.compose.resources.stringResource

/** Height of a worktree's row, which has two lines. */
private val WORKTREE_ROW_HEIGHT = 46.dp

/** Opacity of a worktree whose folder is missing. */
private const val PRUNABLE_ALPHA = 0.5f

/**
 * The Worktrees section of the side panel (fork-only): every worktree of the repository, main one first, with what it
 * has checked out, whether it has changes, how it compares to the base branch and how old its last commit is.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.worktrees(
    worktreesState: WorktreesState,
    onExpand: () -> Unit,
    onWorktreeClicked: (WorktreeInfo) -> Unit,
    onCopyPath: (WorktreeInfo) -> Unit,
    onChooseBase: (branch: String?) -> Unit,
) {
    stickyHeader(key = "header:worktrees") {
        val baseChoice = worktreesState.baseChoice

        SectionHeaderBackground {
            SideMenuHeader(
                text = stringResource(Res.string.side_pane_worktrees_title),
                icon = painterResource(Res.drawable.folder),
                itemsCount = worktreesState.rows.count(),
                isExpanded = worktreesState.isExpanded,
                onExpand = onExpand,
                sortAction = baseChoice?.let { base ->
                    { WorktreesBaseMenuButton(base, onChooseBase) }
                },
            )
        }
    }

    if (worktreesState.isExpanded) {
        if (worktreesState.error != null) {
            item(key = "worktreesError") {
                WorktreesError(worktreesState.error.getErrorText())
            }
        }

        items(worktreesState.rows, key = { "worktree:${it.info.worktree.path}" }) { row ->
            Worktree(
                row = row,
                baseBranch = worktreesState.baseBranch,
                onClick = { onWorktreeClicked(row.info) },
                onCopyPath = { onCopyPath(row.info) },
            )
        }
    }
}

@Composable
private fun WorktreesError(errorText: String) {
    DelayedTooltip(errorText) {
        Text(
            text = stringResource(Res.string.side_pane_worktrees_error),
            color = MaterialTheme.colors.onBackgroundSecondary,
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(MaterialTheme.linesHeight.sidePanelItemHeight)
                .padding(start = 32.dp, end = 16.dp, top = 8.dp),
        )
    }
}

@Composable
private fun Worktree(
    row: WorktreeRow,
    baseBranch: String?,
    onClick: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val worktree = row.info.worktree
    val isPrunable = worktree.prunable != null

    ContextMenu(
        items = {
            mutableListOf<ContextMenuElement>().apply {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.side_pane_worktree_copy_path) },
                    icon = { painterResource(Res.drawable.copy) },
                    onClick = onCopyPath,
                )
            }
        }
    ) {
        DelayedTooltip(worktreeTooltip(row.info, baseBranch)) {
            Row(
                modifier = Modifier
                    .height(WORKTREE_ROW_HEIGHT)
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .alpha(if (isPrunable) PRUNABLE_ALPHA else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(if (worktree.isMain) Res.drawable.source else Res.drawable.folder),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 32.dp, end = 8.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colors.primaryVariant,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.name,
                            fontWeight = if (worktree.isCurrent) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackground,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        if (worktree.locked != null) {
                            Icon(
                                painter = painterResource(Res.drawable.lock),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(12.dp),
                                tint = MaterialTheme.colors.onBackgroundSecondary,
                            )
                        }

                        val trailingLabel = if (isPrunable) {
                            stringResource(Res.string.side_pane_worktree_missing)
                        } else {
                            row.ageLabel
                        }

                        if (trailingLabel != null) {
                            SecondaryLabel(trailingLabel, modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.head.text(),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        val status = row.info.status

                        if (status != null && status.isDirty) {
                            ChangesDot(status, modifier = Modifier.padding(start = 8.dp))
                        }

                        val aheadBehind = row.info.aheadBehindBase

                        if (aheadBehind != null && (aheadBehind.ahead > 0 || aheadBehind.behind > 0)) {
                            SecondaryLabel(aheadBehind.compactText(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colors.onBackgroundSecondary,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** Marks a worktree with uncommitted changes, in the color of conflicts when it has some. */
@Composable
private fun ChangesDot(status: WorktreeStatus, modifier: Modifier = Modifier) {
    val color = if (status.conflicted > 0) MaterialTheme.colors.conflictFile else MaterialTheme.colors.modifyFile

    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

/** Commits ahead and behind, such as `↑3 ↓1`, leaving out a direction without commits. */
internal fun AheadBehind.compactText(): String = listOfNotNull(
    "↑$ahead".takeIf { ahead > 0 },
    "↓$behind".takeIf { behind > 0 },
).joinToString(" ")

@Composable
private fun WorktreeHeadLabel.text(): String = when (this) {
    is WorktreeHeadLabel.OnBranch -> branch
    is WorktreeHeadLabel.Rebasing -> stringResource(Res.string.side_pane_worktree_rebasing, branch)
    is WorktreeHeadLabel.Bisecting -> stringResource(Res.string.side_pane_worktree_bisecting, branch)
    is WorktreeHeadLabel.Detached -> stringResource(Res.string.side_pane_worktree_detached, shortHash)
    WorktreeHeadLabel.Bare -> stringResource(Res.string.side_pane_worktree_bare)
}

/**
 * What a worktree's tooltip says: its folder, then what it has checked out, its changes, how it compares to the base
 * branch and to its upstream, and whether it's locked or its folder is missing.
 */
@Composable
internal fun worktreeTooltip(info: WorktreeInfo, baseBranch: String?): String {
    val worktree = info.worktree
    val status = info.status
    val aheadBehindBase = info.aheadBehindBase
    val upstream = status?.upstream
    val upstreamAheadBehind = status?.upstreamAheadBehind
    val locked = worktree.locked
    val prunable = worktree.prunable

    val lines = mutableListOf(worktree.path)

    when {
        worktree.isMain && worktree.isCurrent ->
            lines += stringResource(Res.string.side_pane_worktree_tooltip_main_current)

        worktree.isMain -> lines += stringResource(Res.string.side_pane_worktree_tooltip_main)
        worktree.isCurrent -> lines += stringResource(Res.string.side_pane_worktree_tooltip_current)
    }

    lines += ""
    lines += when (val head = worktree.headLabel()) {
        is WorktreeHeadLabel.OnBranch -> stringResource(Res.string.side_pane_worktree_tooltip_branch, head.branch)
        else -> head.text().replaceFirstChar { it.uppercase() }
    }

    if (status != null) {
        lines += status.changesText()
    }

    if (baseBranch != null && aheadBehindBase != null) {
        lines += aheadBehindBase.comparedText(baseBranchName(baseBranch))
    }

    if (upstream != null && upstreamAheadBehind != null) {
        lines += upstreamAheadBehind.comparedText(upstream)
    }

    if (locked != null) {
        lines += if (locked.isBlank()) {
            stringResource(Res.string.side_pane_worktree_tooltip_locked)
        } else {
            stringResource(Res.string.side_pane_worktree_tooltip_locked_reason, locked)
        }
    }

    if (prunable != null) {
        lines += ""
        lines += stringResource(Res.string.side_pane_worktree_tooltip_prunable, prunable)
    }

    return lines.joinToString("\n")
}

@Composable
private fun WorktreeStatus.changesText(): String {
    if (!isDirty) {
        return stringResource(Res.string.side_pane_worktree_tooltip_no_changes)
    }

    val parts = listOfNotNull(
        stringResource(Res.string.side_pane_worktree_tooltip_staged, staged).takeIf { staged > 0 },
        stringResource(Res.string.side_pane_worktree_tooltip_unstaged, unstaged).takeIf { unstaged > 0 },
        stringResource(Res.string.side_pane_worktree_tooltip_untracked, untracked).takeIf { untracked > 0 },
        stringResource(Res.string.side_pane_worktree_tooltip_conflicted, conflicted).takeIf { conflicted > 0 },
    )

    return stringResource(Res.string.side_pane_worktree_tooltip_changes, parts.joinToString(", "))
}

@Composable
private fun AheadBehind.comparedText(other: String): String = if (ahead == 0 && behind == 0) {
    stringResource(Res.string.side_pane_worktree_tooltip_up_to_date, other)
} else {
    stringResource(Res.string.side_pane_worktree_tooltip_compared, other, ahead, behind)
}

/**
 * The header button that chooses the branch the worktrees are compared to: muted while Leaf picks it, accent-colored
 * with the branch's name once one is chosen. Its menu goes back to Automatic; other branches are chosen from their
 * right-click menus.
 */
@Composable
private fun WorktreesBaseMenuButton(base: WorktreeBaseBranch, onChooseBase: (branch: String?) -> Unit) {
    val chosen = base.chosen
    val automatic = base.automatic

    val automaticHint = if (automatic != null) {
        stringResource(Res.string.side_pane_worktrees_base_automatic_hint, baseBranchName(automatic))
    } else {
        stringResource(Res.string.side_pane_worktrees_base_automatic_none)
    }

    val chosenHint = when {
        base.chosenExists -> null
        automatic != null -> stringResource(Res.string.side_pane_worktrees_base_missing, baseBranchName(automatic))
        else -> stringResource(Res.string.side_pane_worktrees_base_missing_none)
    }

    val items = buildList {
        add(SortMenuItem.Title(stringResource(Res.string.side_pane_worktrees_base_title)))
        add(
            SortMenuItem.Option(
                label = stringResource(Res.string.side_pane_worktrees_base_automatic),
                hint = automaticHint,
                isChecked = chosen == null,
                closesMenu = true,
                onClick = { if (chosen != null) onChooseBase(null) },
            )
        )

        if (chosen != null) {
            add(
                SortMenuItem.Option(
                    label = baseBranchName(chosen),
                    hint = chosenHint,
                    isChecked = true,
                    closesMenu = true,
                    onClick = {},
                )
            )
        }

        add(SortMenuItem.Divider)
        add(SortMenuItem.Note(stringResource(Res.string.side_pane_worktrees_base_note)))
    }

    SortMenuButton(
        isActive = chosen != null,
        activeLabel = chosen?.let { baseBranchName(it) },
        showFolderIcon = false,
        menuItems = items,
        icon = Res.drawable.compare_arrows,
        tooltip = stringResource(Res.string.side_pane_worktrees_base_tooltip),
    )
}

/** The name of a base branch as the user knows it: `main` for `refs/heads/main`, `origin/main` for a remote one. */
internal fun baseBranchName(branch: String): String = Repository.shortenRefName(branch)
