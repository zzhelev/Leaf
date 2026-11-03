// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.compositionLocalOf
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.isCheckedOut
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.repositoryopen.LogBranchFilterState
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The log's branch filter for the branch menus (fork-only, upstream issue 207), which the log provides to its branch
 * chips as [LocalLogBranchFilter]. Null leaves the items out.
 */
class LogBranchFilterMenu(
    val state: LogBranchFilterState,
    val onSet: (LogBranchFilter) -> Unit,
)

val LocalLogBranchFilter = compositionLocalOf<LogBranchFilterMenu?> { null }

/**
 * "Show only this branch in the log", then "Hide in the log", or "Show in the log" while the filter leaves it out. A
 * local branch's items act on the remote branches of its name too, which would show its line on their own. The branch
 * that is checked out always shows, so it has no Hide.
 */
fun logBranchFilterMenuItems(branch: Branch, menu: LogBranchFilterMenu): List<ContextMenuElement> = buildList {
    val state = menu.state
    val onlyThis = state.showingOnly(branch)

    if (onlyThis != state.filter) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.branch_context_menu_show_only_in_log) },
            icon = { painterResource(Res.drawable.visibility) },
            onClick = { menu.onSet(onlyThis) },
        )
    }

    if (!branch.isCheckedOut(state.currentBranch)) {
        if (state.shows(branch)) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_hide_in_log) },
                icon = { painterResource(Res.drawable.visibility_off) },
                onClick = { menu.onSet(state.hiding(branch)) },
            )
        } else {
            addContextMenu(
                composableLabel = { stringResource(Res.string.branch_context_menu_show_in_log) },
                icon = { painterResource(Res.drawable.visibility) },
                onClick = { menu.onSet(state.showing(branch)) },
            )
        }
    }
}
