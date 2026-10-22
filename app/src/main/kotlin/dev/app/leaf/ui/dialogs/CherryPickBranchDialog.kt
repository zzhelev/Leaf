// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.cherrypick.BranchCherryPick
import dev.app.leaf.domain.cherrypick.CherryPickCandidate
import dev.app.leaf.domain.cherrypick.CherryPickLeftOut
import dev.app.leaf.domain.cherrypick.CherryPickRefusal
import dev.app.leaf.domain.cherrypick.MAX_CHERRY_PICK_COMMITS
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 480.dp

/** The list of commits scrolls past this, so that the dialog fits a laptop's window. */
private val MAX_COMMITS_HEIGHT = 240.dp

private val COMMIT_ROW_HEIGHT = 28.dp

/** The name git gives the current branch when HEAD is detached. */
private const val DETACHED_HEAD = "HEAD"

/**
 * Confirms cherry-picking a branch's commits onto the current branch (fork-only): the commits, each with a check box,
 * those that can't be picked greyed with why, "As one commit", and the commands that run.
 */
@Composable
fun CherryPickBranchDialog(
    viewModel: CherryPickBranchViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    CherryPickBranchDialogContent(
        branch = viewModel.branch,
        state = state,
        onToggle = viewModel::toggle,
        onAsOneCommitChanged = viewModel::setAsOneCommit,
        onConfirm = {
            if (viewModel.confirm()) {
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

/** The dialog for [state], apart from its view model, which tests render. */
@Composable
internal fun CherryPickBranchDialogContent(
    branch: Branch,
    state: CherryPickBranchState,
    onToggle: (String) -> Unit,
    onAsOneCommitChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pick = state.pick
    val branchName = branch.simpleNameWithRemote
    val targetName = pick?.currentBranch?.let(::shortBranchName)

    IconBasedDialog(
        icon = painterResource(Res.drawable.copy),
        title = if (targetName == null) {
            stringResource(Res.string.cherry_pick_dialog_title_loading)
        } else {
            stringResource(Res.string.cherry_pick_dialog_title, targetName)
        },
        subtitle = subtitle(state, branchName, targetName),
        primaryActionText = pluralStringResource(
            Res.plurals.cherry_pick_dialog_confirm,
            state.pickedCount,
            state.pickedCount,
        ),
        isPrimaryActionEnabled = state.canConfirm,
        onDismiss = onDismiss,
        onPrimaryActionClicked = onConfirm,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps long messages on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state.error

            when {
                error != null -> DialogWarning(error.getStyledErrorText())
                pick == null -> DialogNote(stringResource(Res.string.cherry_pick_dialog_checking))
                else -> CherryPickDetails(
                    pick = pick,
                    state = state,
                    branchName = branchName,
                    targetName = checkNotNull(targetName),
                    onToggle = onToggle,
                    onAsOneCommitChanged = onAsOneCommitChanged,
                )
            }
        }
    }
}

@Composable
private fun subtitle(state: CherryPickBranchState, branchName: String, targetName: String?): String {
    val pick = state.pick

    if (pick == null || targetName == null || pick.pickable.isEmpty()) {
        return stringResource(Res.string.cherry_pick_dialog_subtitle_loading, branchName, targetName ?: "…")
    }

    val picked = state.pickedCount
    val total = pick.pickable.size
    val commits = if (picked == total) {
        pluralStringResource(Res.plurals.cherry_pick_dialog_all_commits, total, total)
    } else {
        pluralStringResource(Res.plurals.cherry_pick_dialog_some_commits, total, picked, total)
    }

    val subtitle = if (state.asOneCommit) {
        Res.plurals.cherry_pick_dialog_subtitle_as_one
    } else {
        Res.plurals.cherry_pick_dialog_subtitle
    }

    return pluralStringResource(subtitle, picked, picked, commits, branchName, targetName)
}

@Composable
private fun CherryPickDetails(
    pick: BranchCherryPick,
    state: CherryPickBranchState,
    branchName: String,
    targetName: String,
    onToggle: (String) -> Unit,
    onAsOneCommitChanged: (Boolean) -> Unit,
) {
    if (pick.candidates.isNotEmpty()) {
        CommitList(pick.candidates, state.selected, onToggle)
    }

    val refusal = state.refusal

    // Refused, it says only why. Unticking every commit isn't a refusal worth a warning, only a disabled button.
    when (refusal) {
        null -> {}
        CherryPickRefusal.NoneSelected -> DialogNote(stringResource(Res.string.cherry_pick_refusal_none_selected))
        else -> {
            DialogWarning(refusal.text(pick, branchName, targetName))
            return
        }
    }

    val unstaged = pick.status?.unstaged ?: 0

    if (unstaged > 0) {
        DialogNote(pluralStringResource(Res.plurals.cherry_pick_dialog_unstaged_changes, unstaged, unstaged))
    }

    CheckboxText(
        value = state.asOneCommit,
        onCheckedChange = { onAsOneCommitChanged(!state.asOneCommit) },
        text = stringResource(Res.string.cherry_pick_dialog_as_one_commit),
    )

    val plan = state.plan ?: return

    plan.squashMessage?.let { SquashMessage(it) }

    WorktreeCommands(plan.steps)
}

/** The branch's commits, oldest first, each with a check box; those that can't be picked greyed, with why. */
@Composable
private fun CommitList(
    candidates: List<CherryPickCandidate>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(4.dp)),
    ) {
        ScrollingBox(maxHeight = MAX_COMMITS_HEIGHT, modifier = Modifier.padding(vertical = 2.dp)) {
            for (candidate in candidates) {
                CommitRow(
                    candidate = candidate,
                    isSelected = candidate.commit.hash in selected,
                    onToggle = { onToggle(candidate.commit.hash) },
                )
            }
        }
    }
}

@Composable
private fun CommitRow(
    candidate: CherryPickCandidate,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val commit = candidate.commit
    val leftOut = candidate.leftOut
    val rowModifier = if (leftOut == null) {
        Modifier.handMouseClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onToggle,
        )
    } else {
        Modifier.alpha(LEFT_OUT_ALPHA)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = rowModifier
            .fillMaxWidth()
            .height(COMMIT_ROW_HEIGHT)
            .padding(start = 2.dp, end = 10.dp),
    ) {
        Checkbox(
            checked = leftOut == null && isSelected,
            onCheckedChange = { onToggle() },
            enabled = leftOut == null,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(12.dp),
        )

        Text(
            text = commit.shortHash,
            fontFamily = monoTypography(),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
        )

        Text(
            text = commit.shortMessage,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = when (leftOut) {
                CherryPickLeftOut.MERGE -> stringResource(Res.string.cherry_pick_dialog_left_out_merge)
                // Not "Already on <branch>": a branch's long name left the subject a few letters
                CherryPickLeftOut.ALREADY_APPLIED -> stringResource(Res.string.cherry_pick_dialog_left_out_applied)

                null -> commit.date.toSmartSystemString()
            },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
        )
    }
}

/** A commit that can't be picked shows, faded, so that the list is the branch's whole history since it split. */
private const val LEFT_OUT_ALPHA = 0.55f

@Composable
private fun CherryPickRefusal.text(pick: BranchCherryPick, branchName: String, targetName: String): String =
    when (this) {
        CherryPickRefusal.DetachedHead -> stringResource(Res.string.cherry_pick_refusal_detached)
        CherryPickRefusal.NothingToPick -> if (pick.commitCount == 0) {
            stringResource(Res.string.cherry_pick_refusal_no_commits, branchName, targetName)
        } else {
            stringResource(Res.string.cherry_pick_refusal_nothing_to_pick, branchName, targetName)
        }

        is CherryPickRefusal.TooManyCommits -> stringResource(
            Res.string.cherry_pick_refusal_too_many,
            branchName,
            count,
            targetName,
            MAX_CHERRY_PICK_COMMITS,
        )

        CherryPickRefusal.OperationInProgress -> stringResource(Res.string.cherry_pick_refusal_operation)
        is CherryPickRefusal.StagedChanges ->
            pluralStringResource(Res.plurals.cherry_pick_refusal_staged, files, files)

        CherryPickRefusal.StatusUnknown -> stringResource(Res.string.cherry_pick_refusal_status_unknown)
        CherryPickRefusal.NoneSelected -> stringResource(Res.string.cherry_pick_refusal_none_selected)
    }

/** `feature/x` for `refs/heads/feature/x`. A detached HEAD stays `HEAD`. */
private fun shortBranchName(ref: String): String = when (ref) {
    DETACHED_HEAD -> ref
    else -> ref.removePrefix("refs/heads/")
}
