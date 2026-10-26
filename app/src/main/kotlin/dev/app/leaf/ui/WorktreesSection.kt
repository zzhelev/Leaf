// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Colors
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.AgentSessionLock
import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeMergeKind
import dev.app.leaf.domain.models.WorktreeOrigin
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.worktrees.WorktreeHeadLabel
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.domain.worktrees.branchToMerge
import dev.app.leaf.domain.worktrees.branchToRebase
import dev.app.leaf.domain.worktrees.branchToReview
import dev.app.leaf.domain.worktrees.headLabel
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.onDoubleClick
import dev.app.leaf.theme.addFile
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
import org.jetbrains.compose.resources.pluralStringResource
import org.eclipse.jgit.lib.Repository
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.TimeUnit

/** Height of a worktree's row, which has two lines. */
private val WORKTREE_ROW_HEIGHT = 46.dp

/** A dark amber, readable as text on a light background. */
private val IDLE_TEXT_ON_LIGHT = Color(0xFF9A6700)

/** Opacity of a worktree whose folder is missing. */
internal const val PRUNABLE_ALPHA = 0.5f

/**
 * The Worktrees section of the side panel (fork-only): every worktree of the repository, main one first, with what it
 * has checked out, whether it has changes, how it compares to the base branch and how old its last commit is.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.worktrees(
    worktreesState: WorktreesState,
    onExpand: () -> Unit,
    onWorktreeClicked: (WorktreeInfo) -> Unit,
    onSwitchToWorktree: (WorktreeInfo) -> Unit,
    onOpenInNewTab: (WorktreeInfo) -> Unit,
    onCopyPath: (WorktreeInfo) -> Unit,
    onChooseBase: (branch: String?) -> Unit,
    actions: WorktreeActions = WorktreeActions(),
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
                hoverIcon = { NewWorktreeButton(actions.onNew) },
                sortAction = baseChoice?.let { base ->
                    { WorktreesMenuButton(base, worktreesState.cleanupCount, onChooseBase, actions.onCleanUp) }
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
            WorktreeItem(
                row = row,
                baseBranch = worktreesState.baseBranch,
                mergeTarget = worktreesState.mergeTarget,
                canCleanUp = row.info.merged != null && worktreesState.cleanupCount > 0,
                onClick = { onWorktreeClicked(row.info) },
                onSwitchTo = { onSwitchToWorktree(row.info) },
                onOpenInNewTab = { onOpenInNewTab(row.info) },
                onCopyPath = { onCopyPath(row.info) },
                actions = actions,
            )
        }
    }
}

/**
 * What a worktree's menu does to the worktree itself (fork-only): each opens its dialog, except unlocking, which loses
 * nothing.
 */
data class WorktreeActions(
    val onLock: (Worktree) -> Unit = {},
    val onUnlock: (Worktree) -> Unit = {},
    val onRemove: (Worktree) -> Unit = {},
    val onDiscard: (Worktree) -> Unit = {},
    val onPrune: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onReview: (Worktree) -> Unit = {},
    val onMerge: (Worktree, WorktreeMergeKind) -> Unit = { _, _ -> },
    val onRebase: (Worktree) -> Unit = {},
    val onCleanUp: () -> Unit = {},
    /** "Squash branch into one commit…" for a worktree's branch (`refs/heads/x`), null when it has none to offer. */
    val squashBranch: (branch: String) -> ContextMenuElement? = { null },
    /** "Interactive rebase…" for a worktree's branch, null when it has none to offer. */
    val editBranch: (branch: String) -> ContextMenuElement? = { null },
)

/** The header's button that creates a worktree from the base branch, shown while the header is hovered. */
@Composable
private fun NewWorktreeButton(onNew: () -> Unit) {
    IconButton(
        onClick = onNew,
        modifier = Modifier
            .padding(end = 16.dp)
            .size(16.dp)
            .handOnHover(),
    ) {
        Icon(
            painter = painterResource(Res.drawable.add),
            contentDescription = stringResource(Res.string.side_pane_worktrees_new),
            modifier = Modifier.fillMaxSize(),
            tint = MaterialTheme.colors.onBackground,
        )
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
private fun WorktreeItem(
    row: WorktreeRow,
    baseBranch: String?,
    mergeTarget: String?,
    canCleanUp: Boolean,
    onClick: () -> Unit,
    onSwitchTo: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onCopyPath: () -> Unit,
    actions: WorktreeActions,
) {
    val worktree = row.info.worktree
    val isPrunable = worktree.prunable != null
    // A double click's first click selects the worktree's commit, which recomposes the row with a new lambda
    val currentOnSwitchTo by rememberUpdatedState(onSwitchTo)

    ContextMenu(
        items = {
            worktreeContextMenuItems(
                worktree,
                onSwitchTo,
                onOpenInNewTab,
                onCopyPath,
                actions,
                baseBranch,
                mergeTarget,
                canCleanUp,
            )
        }
    ) {
        DelayedTooltip(worktreeTooltip(row.info, baseBranch)) {
            Row(
                modifier = Modifier
                    .height(WORKTREE_ROW_HEIGHT)
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .run { if (worktree.canSwitchTo) onDoubleClick { currentOnSwitchTo() } else this }
                    .alpha(if (isPrunable) PRUNABLE_ALPHA else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorktreeIcon(worktree, modifier = Modifier.padding(start = 32.dp, end = 8.dp))

                WorktreeRowLines(row, modifier = Modifier.weight(1f).padding(end = 16.dp))
            }
        }
    }
}

/** The icon of [worktree]'s row: the main worktree's differs. */
@Composable
internal fun WorktreeIcon(worktree: Worktree, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(if (worktree.isMain) Res.drawable.source else Res.drawable.folder),
        contentDescription = null,
        modifier = modifier.size(16.dp),
        tint = MaterialTheme.colors.primaryVariant,
    )
}

/**
 * The two lines of a worktree's row, in the side panel and the toolbar's picker: its folder name (bold for the tab's
 * worktree), whether it's locked and how old its commit is, then what it has checked out, whether it has changes and
 * how it compares to the base branch.
 */
@Composable
internal fun WorktreeRowLines(row: WorktreeRow, modifier: Modifier = Modifier) {
    val worktree = row.info.worktree
    val isPrunable = worktree.prunable != null

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The tag follows the name, which ellipsizes first
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    fontWeight = if (worktree.isCurrent) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                val origin = row.info.origin

                if (origin != null) {
                    AgentTag(origin.label, modifier = Modifier.padding(start = 6.dp))
                }
            }

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

            val idleLabel = row.idleLabel
            val ageLabel = row.ageLabel

            when {
                isPrunable -> SecondaryLabel(
                    stringResource(Res.string.side_pane_worktree_missing),
                    modifier = Modifier.padding(start = 8.dp),
                )

                // Fork-only (Phase 4.3a): in place of the commit's age
                idleLabel != null -> SecondaryLabel(
                    stringResource(Res.string.side_pane_worktree_idle, idleLabel),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colors.amberText,
                )

                ageLabel != null -> SecondaryLabel(ageLabel, modifier = Modifier.padding(start = 8.dp))
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

            if (row.info.merged != null) {
                // Fork-only (Phase 4.3a): in place of the commits ahead and behind, which no longer matter
                SecondaryLabel(
                    stringResource(Res.string.side_pane_worktree_merged),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colors.addFile,
                )
            } else if (aheadBehind != null && (aheadBehind.ahead > 0 || aheadBehind.behind > 0)) {
                SecondaryLabel(aheadBehind.compactText(), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * The menu of [worktree]'s row: reviewing its changes when its branch isn't [baseBranch], squash-merging or merging it
 * into [mergeTarget] when it isn't that either, and rebasing it onto [baseBranch]; switching the tab to it, or opening
 * it in a new tab, when the tab can switch to it; for a linked worktree, locking or unlocking it, removing or
 * discarding it, cleaning up merged worktrees when [canCleanUp] (its work is in the base, and something can be
 * removed), and pruning when its folder is missing; and copying its path.
 */
internal fun worktreeContextMenuItems(
    worktree: Worktree,
    onSwitchTo: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onCopyPath: () -> Unit,
    actions: WorktreeActions = WorktreeActions(),
    baseBranch: String? = null,
    mergeTarget: String? = null,
    canCleanUp: Boolean = false,
): List<ContextMenuElement> = mutableListOf<ContextMenuElement>().apply {
    if (worktree.branchToReview(baseBranch) != null) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_review) },
            icon = { painterResource(Res.drawable.visibility) },
            onClick = { actions.onReview(worktree) },
        )
    }

    if (mergeTarget != null && worktree.branchToMerge(baseBranch, mergeTarget) != null) {
        val targetName = baseBranchName(mergeTarget)

        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_squash_merge, targetName) },
            icon = { painterResource(Res.drawable.merge) },
            onClick = { actions.onMerge(worktree, WorktreeMergeKind.SQUASH) },
        )

        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_merge, targetName) },
            icon = { painterResource(Res.drawable.merge) },
            onClick = { actions.onMerge(worktree, WorktreeMergeKind.MERGE) },
        )
    }

    if (baseBranch != null && worktree.branchToRebase(baseBranch, mergeTarget) != null) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_rebase, baseBranchName(baseBranch)) },
            icon = { painterResource(Res.drawable.branch) },
            onClick = { actions.onRebase(worktree) },
        )
    }

    worktree.branch?.let(actions.squashBranch)?.let(::add)
    worktree.branch?.let(actions.editBranch)?.let(::add)

    if (worktree.canSwitchTo) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_switch_to) },
            icon = { painterResource(Res.drawable.start) },
            onClick = onSwitchTo,
        )

        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_open_in_new_tab) },
            icon = { painterResource(Res.drawable.open) },
            onClick = onOpenInNewTab,
        )
    }

    // The main worktree can't be removed, and git doesn't lock or prune it
    if (!worktree.isMain) {
        if (isNotEmpty()) {
            add(ContextMenuElement.ContextSeparator)
        }

        if (worktree.locked == null) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.side_pane_worktree_lock) },
                icon = { painterResource(Res.drawable.lock) },
                onClick = { actions.onLock(worktree) },
            )
        } else {
            addContextMenu(
                composableLabel = { stringResource(Res.string.side_pane_worktree_unlock) },
                icon = { painterResource(Res.drawable.lock_open) },
                onClick = { actions.onUnlock(worktree) },
            )
        }

        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_remove) },
            icon = { painterResource(Res.drawable.remove) },
            onClick = { actions.onRemove(worktree) },
        )

        addContextMenu(
            composableLabel = { stringResource(Res.string.side_pane_worktree_discard) },
            icon = { painterResource(Res.drawable.delete) },
            onClick = { actions.onDiscard(worktree) },
        )

        if (canCleanUp) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.side_pane_worktree_clean_up) },
                icon = { painterResource(Res.drawable.delete) },
                onClick = actions.onCleanUp,
            )
        }

        if (worktree.prunable != null) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.side_pane_worktree_prune) },
                icon = { painterResource(Res.drawable.close) },
                onClick = actions.onPrune,
            )
        }

        add(ContextMenuElement.ContextSeparator)
    }

    addContextMenu(
        composableLabel = { stringResource(Res.string.side_pane_worktree_copy_path) },
        icon = { painterResource(Res.drawable.copy) },
        onClick = onCopyPath,
    )
}

@Composable
private fun SecondaryLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colors.onBackgroundSecondary,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/**
 * The tag of the tool that made a worktree, such as `Claude` (fork-only, see [WorktreeInfo.origin]): on its row, in the
 * toolbar's picker and on its branch's badge.
 */
@Composable
internal fun AgentTag(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = MaterialTheme.colors.primaryVariant,
        style = MaterialTheme.typography.caption,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .border(1.dp, MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
    )
}

/**
 * The amber of "idle", and of what the cleanup dialog asks to look at first: the conflict color, and a darker one on a
 * light background, where the bright one is hard to read as text.
 */
internal val Colors.amberText: Color
    @Composable
    get() = if (isLight) IDLE_TEXT_ON_LIGHT else conflictFile

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
internal fun WorktreeHeadLabel.text(): String = when (this) {
    is WorktreeHeadLabel.OnBranch -> branch
    is WorktreeHeadLabel.Rebasing -> stringResource(Res.string.side_pane_worktree_rebasing, branch)
    is WorktreeHeadLabel.Bisecting -> stringResource(Res.string.side_pane_worktree_bisecting, branch)
    is WorktreeHeadLabel.Detached -> stringResource(Res.string.side_pane_worktree_detached, shortHash)
    WorktreeHeadLabel.Bare -> stringResource(Res.string.side_pane_worktree_bare)
}

/**
 * What a worktree's tooltip says: its folder, then what it has checked out, its changes, how it compares to the base
 * branch and to its upstream, whether its work is in the base and how long nothing changed in it, and whether it's
 * locked or its folder is missing.
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

    info.origin?.let { origin -> lines += origin.tooltipText() }

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

    val merged = info.merged

    if (baseBranch != null && merged != null) {
        lines += merged.tooltipText(baseBranchName(baseBranch))
    }

    val idleSince = info.idleSince

    if (idleSince != null) {
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - idleSince).toInt()
        lines += pluralStringResource(Res.plurals.side_pane_worktree_tooltip_idle, days, days)
    }

    if (upstream != null && upstreamAheadBehind != null) {
        lines += upstreamAheadBehind.comparedText(upstream)
    }

    val agentLock = worktree.agentLock

    if (agentLock != null) {
        lines += agentLock.text()
    } else if (locked != null) {
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
private fun MergedIntoBase.tooltipText(baseBranch: String): String = when (this) {
    MergedIntoBase.COMMITS -> stringResource(Res.string.side_pane_worktree_tooltip_merged_commits, baseBranch)
    MergedIntoBase.CHANGES -> stringResource(Res.string.side_pane_worktree_tooltip_merged_changes, baseBranch)
}

/** How Leaf knows who made a worktree: "Made by Codex: its git dir has codex-thread.json". */
@Composable
private fun WorktreeOrigin.tooltipText(): String = when (this) {
    is WorktreeOrigin.Marked ->
        stringResource(Res.string.side_pane_worktree_tooltip_made_by_marker, marker.tool.fullName, marker.fileName)

    is WorktreeOrigin.InToolFolder ->
        stringResource(Res.string.side_pane_worktree_tooltip_made_by_folder, tool.fullName, pattern)

    is WorktreeOrigin.Labelled -> stringResource(Res.string.side_pane_worktree_tooltip_labelled, label, rule.pattern)
}

/** Whether the agent's session that locked a worktree still runs, in place of the lock's raw reason. */
@Composable
internal fun AgentSessionLock.text(): String = if (isRunning) {
    stringResource(Res.string.side_pane_worktree_tooltip_locked_session_running, tool.fullName)
} else {
    stringResource(Res.string.side_pane_worktree_tooltip_locked_session_ended, tool.fullName)
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
 * The section's menu button (⋯): muted while Leaf picks the branch the worktrees are compared to, accent-colored with
 * the branch's name once one is chosen. Its menu goes back to Automatic (other branches are chosen from their
 * right-click menus), and offers "Clean up merged worktrees…" while [cleanupCount] worktrees can be removed.
 */
@Composable
private fun WorktreesMenuButton(
    base: WorktreeBaseBranch,
    cleanupCount: Int,
    onChooseBase: (branch: String?) -> Unit,
    onCleanUp: () -> Unit,
) {
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

        if (cleanupCount > 0) {
            add(SortMenuItem.Divider)
            add(
                SortMenuItem.Option(
                    label = stringResource(Res.string.side_pane_worktree_clean_up),
                    hint = pluralStringResource(Res.plurals.side_pane_worktrees_clean_up_hint, cleanupCount, cleanupCount),
                    isChecked = false,
                    closesMenu = true,
                    onClick = onCleanUp,
                )
            )
        }
    }

    SortMenuButton(
        isActive = chosen != null,
        activeLabel = chosen?.let { baseBranchName(it) },
        showFolderIcon = false,
        menuItems = items,
        icon = Res.drawable.more_horiz,
        tooltip = stringResource(Res.string.side_pane_worktrees_menu_tooltip),
    )
}

/** The name of a base branch as the user knows it: `main` for `refs/heads/main`, `origin/main` for a remote one. */
internal fun baseBranchName(branch: String): String = Repository.shortenRefName(branch)
