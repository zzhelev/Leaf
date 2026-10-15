// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.CleanupCandidate
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.worktrees.name
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.AgentTag
import dev.app.leaf.ui.amberText
import dev.app.leaf.ui.baseBranchName
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

private val CONTENT_WIDTH = 480.dp

/** The list scrolls past this height, so that the commands and the buttons stay in the window. */
private val MAX_LIST_HEIGHT = 300.dp

private val MAX_COMMANDS_HEIGHT = 160.dp

/** Opacity of a worktree in use by an agent's running session, which can't be checked. */
private const val IN_USE_ALPHA = 0.5f

/** A checkbox's room: 12 dp with 8 dp around it, as in [CheckboxText]. */
private val CHECKBOX_ROOM = 28.dp

/**
 * "Clean up merged worktrees" (fork-only, Phase 4.3b): the worktrees whose work is in the base and the ones whose folder
 * is missing, each with a checkbox, those that lose nothing checked at first, then their branches and the commands
 * that run.
 */
@Composable
fun CleanUpWorktreesDialog(
    viewModel: CleanUpWorktreesViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    CleanUpWorktreesDialogContent(
        state = state,
        onCheckedChange = viewModel::setChecked,
        onDeleteBranchesChange = viewModel::setDeleteBranches,
        onConfirm = {
            if (viewModel.confirm()) {
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun CleanUpWorktreesDialogContent(
    state: CleanUpWorktreesState,
    onCheckedChange: (CleanupCandidate, Boolean) -> Unit,
    onDeleteBranchesChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val base = state.cleanup?.list?.baseBranch?.let { baseBranchName(it) }
    val count = state.selected.size

    IconBasedDialog(
        icon = painterResource(Res.drawable.delete),
        title = stringResource(Res.string.clean_up_worktrees_dialog_title),
        subtitle = if (base != null) {
            stringResource(Res.string.clean_up_worktrees_dialog_subtitle, base)
        } else {
            stringResource(Res.string.clean_up_worktrees_dialog_subtitle_no_base)
        },
        primaryActionText = if (count > 0) {
            pluralStringResource(Res.plurals.clean_up_worktrees_dialog_remove, count, count)
        } else {
            stringResource(Res.string.clean_up_worktrees_dialog_remove_none)
        },
        isPrimaryActionEnabled = state.canConfirm,
        onDismiss = onDismiss,
        onPrimaryActionClicked = onConfirm,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps long reasons on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state.error

            when {
                error != null -> DialogWarning(error.getStyledErrorText())
                state.cleanup == null -> DialogNote(stringResource(Res.string.clean_up_worktrees_dialog_checking))
                state.candidates.isEmpty() -> DialogNote(stringResource(Res.string.clean_up_worktrees_dialog_nothing))
                else -> CleanupDetails(state, base, onCheckedChange, onDeleteBranchesChange)
            }
        }
    }
}

@Composable
private fun CleanupDetails(
    state: CleanUpWorktreesState,
    base: String?,
    onCheckedChange: (CleanupCandidate, Boolean) -> Unit,
    onDeleteBranchesChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .heightIn(max = MAX_LIST_HEIGHT)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (candidate in state.candidates) {
            val path = candidate.worktree.path

            CandidateRow(
                candidate = candidate,
                isChecked = path in state.checked && !candidate.isInUse,
                base = base,
                onCheckedChange = { onCheckedChange(candidate, it) },
            )
        }
    }

    if (state.hasBranches) {
        CheckboxText(
            value = state.deleteBranches,
            onCheckedChange = { onDeleteBranchesChange(!state.deleteBranches) },
            text = stringResource(Res.string.clean_up_worktrees_dialog_delete_branches),
        )
    }

    if (state.selected.any { !it.isMissing }) {
        DialogNote(stringResource(Res.string.clean_up_worktrees_dialog_ignored_files))
    }

    if (state.steps.isNotEmpty()) {
        Column(
            modifier = Modifier
                .heightIn(max = MAX_COMMANDS_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            WorktreeCommands(state.steps)
        }
    }
}

/**
 * A worktree that cleaning up offers: its checkbox, its name, its tag and what it has checked out, then why it's
 * offered, and what to look at before checking it, in amber.
 */
@Composable
private fun CandidateRow(
    candidate: CleanupCandidate,
    isChecked: Boolean,
    base: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    val worktree = candidate.worktree
    val canCheck = !candidate.isInUse

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .alpha(if (canCheck) 1f else IN_USE_ALPHA)
            .run {
                if (canCheck) {
                    handMouseClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onCheckedChange(!isChecked) },
                    )
                } else {
                    this
                }
            },
    ) {
        if (canCheck) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .padding(all = 8.dp)
                    .size(12.dp),
            )
        } else {
            Spacer(Modifier.width(CHECKBOX_ROOM))
        }

        Column(modifier = Modifier.padding(top = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = worktree.name,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                val origin = candidate.info.origin

                if (origin != null) {
                    AgentTag(origin.label, modifier = Modifier.padding(start = 6.dp))
                }

                val checkedOut = checkedOutText(candidate)

                if (checkedOut != null) {
                    Text(
                        text = checkedOut,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            for ((text, color) in reasonLines(candidate, base)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.caption,
                    color = color,
                )
            }
        }
    }
}

/** What [candidate] has checked out: its branch, or the commit it's detached at. */
@Composable
private fun checkedOutText(candidate: CleanupCandidate): String? {
    val worktree = candidate.worktree
    val branch = worktree.branch
    val head = worktree.headSha

    return when {
        branch != null -> baseBranchName(branch)
        head != null -> stringResource(Res.string.side_pane_worktree_detached, head.take(7))
        else -> null
    }
}

/**
 * The lines under [candidate]'s name: why it's offered (green when its work is in [base]), then what keeps it from
 * being checked at first, in amber, or that an agent's running session uses it.
 */
@Composable
private fun reasonLines(candidate: CleanupCandidate, base: String?): List<Pair<String, Color>> {
    val secondary = MaterialTheme.colors.onBackgroundSecondary
    val amber = MaterialTheme.colors.amberText
    val green = MaterialTheme.colors.addFile
    val worktree = candidate.worktree
    val agentLock = worktree.agentLock
    val baseName = base.orEmpty()

    return buildList {
        when (candidate.info.merged) {
            MergedIntoBase.COMMITS ->
                add(stringResource(Res.string.side_pane_worktree_tooltip_merged_commits, baseName) to green)

            MergedIntoBase.CHANGES ->
                add(stringResource(Res.string.side_pane_worktree_tooltip_merged_changes, baseName) to green)

            null -> {}
        }

        if (candidate.isMissing) {
            val keepsBranch = candidate.info.merged == null && worktree.branch != null && base != null

            add(
                if (keepsBranch) {
                    stringResource(Res.string.clean_up_worktrees_dialog_missing_branch_kept, baseName) to secondary
                } else {
                    stringResource(Res.string.clean_up_worktrees_dialog_missing) to secondary
                }
            )
        }

        if (agentLock != null && agentLock.isRunning) {
            add(stringResource(Res.string.clean_up_worktrees_dialog_in_use, agentLock.tool.fullName) to secondary)
            return@buildList
        }

        if (candidate.hasUncommittedChanges) {
            val changes = candidate.changes

            add(
                if (changes != null) {
                    pluralStringResource(Res.plurals.clean_up_worktrees_dialog_changes, changes, changes) to amber
                } else {
                    stringResource(Res.string.clean_up_worktrees_dialog_changes_unknown) to amber
                }
            )
        }

        val recentChange = candidate.recentChange

        if (recentChange != null) {
            val minutes = (System.currentTimeMillis() - recentChange).coerceAtLeast(0).milliseconds.inWholeMinutes.toInt()

            add(
                if (minutes == 0) {
                    stringResource(Res.string.clean_up_worktrees_dialog_recent_change_now) to amber
                } else {
                    pluralStringResource(Res.plurals.clean_up_worktrees_dialog_recent_change, minutes, minutes) to amber
                }
            )
        }

        val locked = worktree.locked

        when {
            locked == null -> {}
            agentLock != null -> add(
                stringResource(Res.string.clean_up_worktrees_dialog_locked_session_ended, agentLock.tool.fullName) to
                    secondary
            )

            locked.isBlank() -> add(stringResource(Res.string.clean_up_worktrees_dialog_locked) to amber)
            else -> add(stringResource(Res.string.clean_up_worktrees_dialog_locked_reason, locked) to amber)
        }
    }
}
