// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.DeletionRemote
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.sorting.formatTimeAgo
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.amberText
import dev.app.leaf.ui.baseBranchName
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 520.dp

/** The list scrolls past this height, so that the buttons stay in the window. */
private val MAX_LIST_HEIGHT = 320.dp

/** Opacity of a branch that can't be deleted. */
private const val REFUSED_ALPHA = 0.55f

/** A checkbox's room: 12 dp with 8 dp around it, as in [dev.app.leaf.ui.components.CheckboxText]. */
private val CHECKBOX_ROOM = 28.dp

/**
 * "Delete branches" and "Delete merged branches" (fork-only, upstream issue 116): every branch with a checkbox and what
 * deleting it loses, the ones that lose nothing checked at first. Delete runs one task, and the dialog stays open to
 * show what happened to each branch, which a toast would not keep on screen.
 *
 * For a remote's branches, it deletes them on the server: the remote's URL and when it was last fetched head the list,
 * each branch says who made its tip and when, the ones whose names look long-lived start unchecked, and the button is
 * the style's destructive one. While git runs, Cancel stops it.
 */
@Composable
fun DeleteBranchesDialog(
    viewModel: DeleteBranchesViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    DeleteBranchesDialogContent(
        state = state,
        onCheckedChange = viewModel::setChecked,
        onCheckAll = viewModel::checkAll,
        onCheckSafe = viewModel::checkSafe,
        onCheckNone = viewModel::checkNone,
        onConfirm = viewModel::delete,
        onCopyList = { scope.launch { clipboard.setClipboardText(state.result?.deletedList().orEmpty()) } },
        onDismiss = onDismiss,
        onStop = viewModel::stopDeleting,
    )
}

@Composable
fun DeleteBranchesDialogContent(
    state: DeleteBranchesState,
    onCheckedChange: (name: String, isChecked: Boolean) -> Unit,
    onCheckAll: () -> Unit,
    onCheckSafe: () -> Unit,
    onCheckNone: () -> Unit,
    onConfirm: () -> Unit,
    onCopyList: () -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit = {},
) {
    val plan = state.plan
    val result = state.result
    val mergedOnly = state.request.mergedOnly
    val remote = state.request.remote
    val base = plan?.base?.let { baseBranchName(it) }
    val count = state.toDelete.size
    val isDone = result != null || state.wasStopped

    IconBasedDialog(
        icon = painterResource(Res.drawable.delete),
        title = when {
            result != null -> resultTitle(result, remote)
            remote != null && mergedOnly -> stringResource(Res.string.delete_remote_branches_dialog_title_merged, remote)
            remote != null -> stringResource(Res.string.delete_remote_branches_dialog_title, remote)
            mergedOnly -> stringResource(Res.string.delete_branches_dialog_title_merged)
            else -> stringResource(Res.string.delete_branches_dialog_title)
        },
        subtitle = when {
            result != null -> resultSubtitle(result)
            plan == null -> stringResource(Res.string.delete_branches_dialog_subtitle_loading)
            mergedOnly -> if (base != null) stringResource(Res.string.delete_branches_dialog_subtitle_merged, base) else ""
            else -> pluralStringResource(Res.plurals.delete_branches_dialog_subtitle, plan.rows.size, plan.rows.size)
        },
        primaryActionText = when {
            isDone -> stringResource(Res.string.delete_branches_dialog_result_close)
            remote != null && count > 0 ->
                pluralStringResource(Res.plurals.delete_remote_branches_dialog_delete, count, count, remote)

            count > 0 -> pluralStringResource(Res.plurals.delete_branches_dialog_delete, count, count)
            else -> stringResource(Res.string.delete_branches_dialog_delete_none)
        },
        isPrimaryActionEnabled = isDone || state.canConfirm,
        showCancelAction = !isDone,
        // Stopping git while it deletes on the server: what it deleted by then is deleted
        cancelActionText = if (state.isDeleting && remote != null) {
            stringResource(Res.string.delete_remote_branches_dialog_stop)
        } else {
            stringResource(Res.string.generic_button_cancel)
        },
        secondaryActionText = if (result != null && result.deleted.isNotEmpty()) {
            stringResource(Res.string.delete_branches_dialog_result_copy)
        } else {
            null
        },
        onSecondaryActionClicked = onCopyList,
        // Leaving half way would stop the deleting between two branches, with nothing to show for it. A remote's
        // stop instead, and the dialog says that some may be gone
        onDismiss = {
            when {
                state.isDeleting && remote != null -> onStop()
                !state.isDeleting -> onDismiss()
            }
        },
        onPrimaryActionClicked = { if (isDone) onDismiss() else onConfirm() },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
        primaryActionColors = if (remote != null && !isDone) MaterialTheme.styleColors.destructive else null,
    ) {
        // The dialog is as wide as its content, so this keeps long reasons on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state.error

            plan?.remote?.let { RemoteLine(it) }

            when {
                result != null -> ResultDetails(result, remote)
                state.wasStopped -> DialogWarning(stringResource(Res.string.delete_remote_branches_dialog_stopped, remote.orEmpty()))
                plan == null && error == null -> DialogNote(stringResource(Res.string.delete_branches_dialog_checking))
                plan == null -> {}
                plan.rows.isEmpty() -> DialogNote(emptyText(mergedOnly, base, remote))
                else -> PlanDetails(state, plan, base, onCheckedChange, onCheckAll, onCheckSafe, onCheckNone)
            }

            if (state.isDeleting && remote != null) {
                DeletingNote(remote)
            }

            if (error != null) {
                DialogWarning(error.getStyledErrorText())
            }
        }
    }
}

@Composable
private fun emptyText(mergedOnly: Boolean, base: String?, remote: String?): String = when {
    !mergedOnly -> stringResource(Res.string.delete_branches_dialog_none_found)
    base == null -> noBaseText(remote)
    else -> stringResource(Res.string.delete_branches_dialog_nothing_merged, base)
}

@Composable
private fun noBaseText(remote: String?): String = if (remote != null) {
    stringResource(Res.string.delete_remote_branches_dialog_no_base, remote)
} else {
    stringResource(Res.string.delete_branches_dialog_no_base)
}

@Composable
private fun resultTitle(result: BranchesDeletion, remote: String?): String {
    val deleted = result.deleted.size
    val total = result.outcomes.size

    return when {
        remote != null && deleted == 0 -> stringResource(Res.string.delete_remote_branches_dialog_result_none, remote)
        remote != null && deleted == total ->
            pluralStringResource(Res.plurals.delete_remote_branches_dialog_result_title, deleted, deleted, remote)

        remote != null -> stringResource(Res.string.delete_remote_branches_dialog_result_title_partial, deleted, total, remote)
        deleted == 0 -> stringResource(Res.string.delete_branches_dialog_result_none)
        deleted == total -> pluralStringResource(Res.plurals.delete_branches_dialog_result_title, deleted, deleted)
        else -> stringResource(Res.string.delete_branches_dialog_result_title_partial, deleted, total)
    }
}

/** The remote's URL, and when it was last fetched, which says how old what the list shows is. */
@Composable
private fun RemoteLine(remote: DeletionRemote) {
    val fetched = remote.lastFetchedMillis?.let {
        stringResource(Res.string.delete_remote_branches_dialog_last_fetched, formatTimeAgo(it, System.currentTimeMillis()))
    }

    if (remote.url == null && fetched == null) return

    Text(
        text = buildAnnotatedString {
            if (remote.url != null) {
                withStyle(SpanStyle(fontFamily = monoTypography())) { append(remote.url) }
            }

            if (remote.url != null && fetched != null) append(" · ")
            if (fetched != null) append(fetched)
        },
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** While git deletes on the server: Cancel, now Stop, kills it. */
@Composable
private fun DeletingNote(remote: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colors.primaryVariant,
        )

        Text(
            text = stringResource(Res.string.delete_remote_branches_dialog_deleting, remote),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun resultSubtitle(result: BranchesDeletion): String {
    val kept = result.kept.size

    return if (kept > 0) {
        pluralStringResource(Res.plurals.delete_branches_dialog_result_kept, kept, kept)
    } else {
        stringResource(Res.string.delete_branches_dialog_result_all_gone)
    }
}

@Composable
private fun PlanDetails(
    state: DeleteBranchesState,
    plan: BranchDeletionPlan,
    base: String?,
    onCheckedChange: (String, Boolean) -> Unit,
    onCheckAll: () -> Unit,
    onCheckSafe: () -> Unit,
    onCheckNone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        Text(
            text = stringResource(Res.string.delete_branches_dialog_count, state.toDelete.size, plan.rows.size),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = stringResource(Res.string.delete_branches_dialog_check),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )

        CheckLink(stringResource(Res.string.delete_branches_dialog_check_all), onCheckAll)
        CheckLink(stringResource(Res.string.delete_branches_dialog_check_safe), onCheckSafe)
        CheckLink(stringResource(Res.string.delete_branches_dialog_check_none), onCheckNone)
    }

    ScrollingBox(maxHeight = MAX_LIST_HEIGHT, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (info in plan.rows) {
            BranchRow(
                info = info,
                kind = plan.kindOf(info),
                isChecked = info.branch.name in state.checked,
                base = base,
                remote = state.request.remote,
                looksLongLived = plan.looksLongLived(info),
                isEnabled = !state.isDeleting,
                onCheckedChange = { onCheckedChange(info.branch.name, it) },
            )
        }
    }

    if (base == null) {
        ListNote(noBaseText(state.request.remote))
    }

    if (state.request.remote != null) {
        ListNote(stringResource(Res.string.delete_remote_branches_dialog_lease))

        if (state.checksUnmerged) {
            ListNote(stringResource(Res.string.delete_remote_branches_dialog_unmerged_warning), MaterialTheme.colors.amberText)
        }
    }
}

/** A note under a list, in line with the list's rows. */
@Composable
private fun ListNote(text: String, color: Color = MaterialTheme.colors.onBackgroundSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = color,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun CheckLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.primaryVariant,
        modifier = Modifier
            .handMouseClickable(onClick = onClick)
            .padding(start = 8.dp),
    )
}

/**
 * A branch: its checkbox, its name, then what deleting it loses, in green when nothing is lost, in amber when commits
 * would be left on no branch, and why it can't be deleted otherwise, dimmed. The commit it points to ends the line,
 * which `git branch <name> <commit>` brings back, and for a remote's, who made it and when. A remote's branch that
 * [looksLongLived] says so, muted, as it starts unchecked.
 */
@Composable
private fun BranchRow(
    info: BranchDeletionInfo,
    kind: BranchDeletionKind,
    isChecked: Boolean,
    base: String?,
    remote: String?,
    looksLongLived: Boolean,
    isEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val canCheck = kind !is BranchDeletionKind.Refused && isEnabled

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .alpha(if (kind is BranchDeletionKind.Refused) REFUSED_ALPHA else 1f)
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
        if (kind !is BranchDeletionKind.Refused) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                enabled = canCheck,
                modifier = Modifier
                    .padding(all = 8.dp)
                    .size(12.dp),
            )
        } else {
            Spacer(Modifier.width(CHECKBOX_ROOM))
        }

        Column(modifier = Modifier.padding(top = 5.dp)) {
            Text(
                text = info.branch.simpleName,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val (status, statusColor) = statusLine(kind, base, remote, info.branch.shortTip)
            val (text, color) = if (looksLongLived && kind !is BranchDeletionKind.Refused) {
                stringResource(Res.string.delete_remote_branches_dialog_long_lived, status) to
                    MaterialTheme.colors.onBackgroundSecondary
            } else {
                status to statusColor
            }
            val byline = info.lastCommit?.let { "${it.author}, ${formatTimeAgo(it.timeMillis, System.currentTimeMillis())}" }

            Text(
                text = withCommit(text, info.branch.shortTip, byline),
                style = MaterialTheme.typography.caption,
                color = color,
            )
        }
    }
}

/** What deleting a branch with this [kind] does, and the color that says how much to look at it. */
@Composable
private fun statusLine(kind: BranchDeletionKind, base: String?, remote: String?, shortTip: String): Pair<String, Color> {
    val secondary = MaterialTheme.colors.onBackgroundSecondary
    val amber = MaterialTheme.colors.amberText
    val green = MaterialTheme.colors.addFile
    val baseName = base.orEmpty()

    return when (kind) {
        is BranchDeletionKind.Refused -> refusalText(kind.reason, remote, shortTip) to secondary

        is BranchDeletionKind.Merged -> when {
            kind.into == MergedIntoBase.COMMITS -> stringResource(Res.string.delete_branches_dialog_merged, baseName)
            kind.commitsOnlyHere == 0 -> stringResource(Res.string.delete_branches_dialog_squash_merged, baseName)
            else -> pluralStringResource(
                Res.plurals.delete_branches_dialog_squash_merged_commits,
                kind.commitsOnlyHere,
                kind.commitsOnlyHere,
                baseName,
            )
        } to green

        BranchDeletionKind.OnOtherRefs -> stringResource(Res.string.delete_branches_dialog_on_other_refs) to secondary

        is BranchDeletionKind.Unmerged -> pluralStringResource(
            Res.plurals.delete_branches_dialog_unmerged,
            kind.commitsOnlyHere,
            kind.commitsOnlyHere,
        ) to amber
    }
}

/** Why a branch whose tip was [shortTip] stays, for a refused row of the list and for the result. */
@Composable
private fun refusalText(reason: BranchKept, remote: String?, shortTip: String): String {
    val remoteName = remote.orEmpty()

    return when (reason) {
        is BranchKept.UsedByWorktree -> usedByText(reason)
        BranchKept.RemoteHead -> stringResource(Res.string.delete_remote_branches_dialog_head, remoteName)
        is BranchKept.RemoteDefaultBranch -> if (reason.isNamedByRemote) {
            stringResource(Res.string.delete_remote_branches_dialog_default_branch, remoteName)
        } else {
            stringResource(Res.string.delete_remote_branches_dialog_base_copy, remoteName)
        }

        BranchKept.NoLongerExists -> stringResource(Res.string.delete_branches_dialog_result_gone)
        is BranchKept.Moved -> stringResource(Res.string.delete_branches_dialog_result_moved, shortTip, reason.shortNow)
        BranchKept.ChangedOnRemote -> stringResource(Res.string.delete_remote_branches_dialog_result_changed, remoteName)
        is BranchKept.RefusedByRemote ->
            stringResource(Res.string.delete_remote_branches_dialog_result_refused, remoteName, reason.reason)

        is BranchKept.Failed -> stringResource(Res.string.delete_branches_dialog_result_failed, reason.message)
    }
}

/** Which worktree uses the branch and how, the tab's own as "this worktree". */
@Composable
private fun usedByText(reason: BranchKept.UsedByWorktree): String {
    val folder = abbreviateHome(reason.path)

    return when (reason.use) {
        WorktreeBranchUse.CheckedOut ->
            if (reason.isHere) {
                stringResource(Res.string.delete_branches_dialog_used_here)
            } else {
                stringResource(Res.string.delete_branches_dialog_used_by, folder)
            }

        WorktreeBranchUse.Rebasing ->
            if (reason.isHere) {
                stringResource(Res.string.delete_branches_dialog_rebased_here)
            } else {
                stringResource(Res.string.delete_branches_dialog_rebased_by, folder)
            }

        WorktreeBranchUse.Bisecting ->
            if (reason.isHere) {
                stringResource(Res.string.delete_branches_dialog_bisected_here)
            } else {
                stringResource(Res.string.delete_branches_dialog_bisected_by, folder)
            }
    }
}

/** [text] and the short commit hash, in a monospace font, after a dot, then who made it and when, muted. */
@Composable
private fun withCommit(text: String, shortHash: String, byline: String? = null): AnnotatedString {
    val mono = SpanStyle(fontFamily = monoTypography())
    val muted = SpanStyle(color = MaterialTheme.colors.onBackgroundSecondary)

    return buildAnnotatedString {
        append(text)
        append(" · ")
        withStyle(mono) { append(shortHash) }

        if (byline != null) {
            withStyle(muted) {
                append(" · ")
                append(byline)
            }
        }
    }
}

/**
 * What happened to each branch: the deleted ones with the commit they pointed to, and the ones that stayed with why.
 * The list stays until the user closes the dialog.
 */
@Composable
private fun ResultDetails(result: BranchesDeletion, remote: String?) {
    ScrollingBox(maxHeight = MAX_LIST_HEIGHT, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (result.deleted.isNotEmpty()) {
            ResultHeader(stringResource(Res.string.delete_branches_dialog_result_deleted))

            for (branch in result.deleted) {
                ResultRow(
                    icon = Res.drawable.check,
                    iconTint = MaterialTheme.colors.addFile,
                    name = branch.simpleName,
                    detail = stringResource(Res.string.delete_branches_dialog_result_was),
                    commit = branch.shortTip,
                    detailColor = MaterialTheme.colors.onBackgroundSecondary,
                )
            }
        }

        if (result.kept.isNotEmpty()) {
            ResultHeader(stringResource(Res.string.delete_branches_dialog_result_not_deleted))

            for (kept in result.kept) {
                ResultRow(
                    icon = Res.drawable.warning,
                    iconTint = MaterialTheme.colors.amberText,
                    name = kept.branch.simpleName,
                    detail = keptText(kept, remote),
                    commit = null,
                    detailColor = MaterialTheme.colors.amberText,
                )
            }
        }
    }

    if (result.deleted.isNotEmpty()) {
        ListNote(
            if (remote != null) {
                stringResource(Res.string.delete_remote_branches_dialog_result_restore, remote)
            } else {
                stringResource(Res.string.delete_branches_dialog_result_restore)
            }
        )
    }

    val remoteMessage = result.remoteMessage

    if (remote != null && remoteMessage != null) {
        ListNote(stringResource(Res.string.delete_remote_branches_dialog_result_server_said, remote))

        Text(
            text = remoteMessage,
            style = MaterialTheme.typography.caption,
            fontFamily = monoTypography(),
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun keptText(kept: BranchDeletionOutcome.Kept, remote: String?): String =
    refusalText(kept.reason, remote, kept.branch.shortTip)

@Composable
private fun ResultHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}

@Composable
private fun ResultRow(
    icon: DrawableResource,
    iconTint: Color,
    name: String,
    detail: String,
    commit: String?,
    detailColor: Color,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 3.dp)
                .size(14.dp),
        )

        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = if (commit != null) {
                    buildAnnotatedString {
                        append(detail)
                        append(" ")
                        withStyle(SpanStyle(fontFamily = monoTypography())) { append(commit) }
                    }
                } else {
                    AnnotatedString(detail)
                },
                style = MaterialTheme.typography.caption,
                color = detailColor,
            )
        }
    }
}
