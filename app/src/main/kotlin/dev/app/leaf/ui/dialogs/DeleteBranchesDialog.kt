// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.app.leaf.domain.branches.BranchCopy
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.DeletionRemote
import dev.app.leaf.domain.branches.offersCopies
import dev.app.leaf.domain.branches.remoteAndName
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.errors.SshNeedsGitError
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
import dev.app.leaf.ui.components.SecondaryButton
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
    onOpenSettings: () -> Unit,
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
        onStopChecking = viewModel::stopChecking,
        onDeletesCopiesChange = viewModel::setDeletesCopies,
        onDeleteAnyway = viewModel::deleteAnyway,
        onUseGit = viewModel::useGitForRemoteOperations,
        onOpenSettings = onOpenSettings,
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
    onStopChecking: () -> Unit = {},
    onDeletesCopiesChange: (Boolean) -> Unit = {},
    onDeleteAnyway: () -> Unit = {},
    onUseGit: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val plan = state.plan
    val result = state.result
    val mergedOnly = state.request.mergedOnly
    val remote = state.request.remote
    val base = plan?.base?.let { baseBranchName(it) }
    val count = state.toDelete.size
    val copies = state.copiesToDelete
    val copyRemotes = copiesRemoteName(copies.values.map { it.remote })
    val isDone = result != null || state.wasStopped
    // Deleting a remote's branches needs git: the button does what the error asks for, where it can
    val gitFix = (state.error as? RemoteBranchesNeedGitError)?.reason?.let { gitFixFor(it) }.takeUnless { isDone }

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
            gitFix == GitFix.USE_GIT -> stringResource(Res.string.delete_remote_branches_dialog_use_git)
            gitFix == GitFix.OPEN_SETTINGS -> stringResource(Res.string.delete_remote_branches_dialog_open_settings)
            copies.isNotEmpty() && count > 0 ->
                stringResource(Res.string.delete_branches_dialog_delete_with_copies, count, copies.size, copyRemotes)

            remote != null && count > 0 ->
                pluralStringResource(Res.plurals.delete_remote_branches_dialog_delete, count, count, remote)

            count > 0 -> pluralStringResource(Res.plurals.delete_branches_dialog_delete, count, count)
            else -> stringResource(Res.string.delete_branches_dialog_delete_none)
        },
        // Deleting anyway runs with the result on screen, and Stop stops it
        isPrimaryActionEnabled = (isDone && !state.isDeleting) || gitFix != null || state.canConfirm,
        // While git deletes on a server, only "Stop waiting", after a while: the server carries out what it was asked
        showCancelAction = if (state.deletingOn != null) {
            state.canStopWaiting
        } else {
            !isDone || state.isChecking || state.isRechecking
        },
        cancelActionText = when {
            state.deletingOn != null -> stringResource(Res.string.delete_remote_branches_dialog_stop)
            state.isChecking || state.isRechecking -> stringResource(Res.string.delete_remote_branches_dialog_stop_checking)
            else -> stringResource(Res.string.generic_button_cancel)
        },
        secondaryActionText = if (result != null && (result.deleted.isNotEmpty() || result.deletedCopies.isNotEmpty())) {
            stringResource(Res.string.delete_branches_dialog_result_copy)
        } else {
            null
        },
        onSecondaryActionClicked = onCopyList,
        // Leaving half way would stop the deleting between two branches, with nothing to show for it. A remote's
        // stop instead, and the dialog says that some may be gone
        onDismiss = {
            when {
                state.deletingOn != null -> if (state.canStopWaiting) onStop()
                state.isChecking || state.isRechecking -> onStopChecking()
                !state.isDeleting -> onDismiss()
            }
        },
        onPrimaryActionClicked = {
            when {
                isDone -> onDismiss()
                gitFix == GitFix.USE_GIT -> onUseGit()
                gitFix == GitFix.OPEN_SETTINGS -> onOpenSettings()
                else -> onConfirm()
            }
        },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
        primaryActionColors = if ((remote != null || copies.isNotEmpty()) && !isDone && gitFix == null) {
            MaterialTheme.styleColors.destructive
        } else {
            null
        },
    ) {
        // The dialog is as wide as its content, so this keeps long reasons on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state.error

            plan?.remote?.let { RemoteLine(it) }

            when {
                state.isChecking -> ProgressNote(
                    stringResource(Res.string.delete_remote_branches_dialog_checking, remote ?: copyRemotes)
                )

                result != null -> ResultDetails(
                    result = result,
                    remote = remote,
                    copiesAsked = state.copiesToDelete,
                    canDeleteAnyway = !state.isDeleting && !state.isChecking && !state.isRechecking,
                    onDeleteAnyway = onDeleteAnyway,
                )
                state.wasStopped -> DialogWarning(stringResource(Res.string.delete_remote_branches_dialog_stopped, remote.orEmpty()))
                plan == null && error == null -> DialogNote(stringResource(Res.string.delete_branches_dialog_checking))
                plan == null -> {}
                plan.rows.isEmpty() -> DialogNote(emptyText(mergedOnly, base, remote))
                else -> PlanDetails(
                    state, plan, base, onCheckedChange, onCheckAll, onCheckSafe, onCheckNone, onDeletesCopiesChange,
                )
            }

            state.deletingOn?.let { on ->
                ProgressNote(stringResource(Res.string.delete_remote_branches_dialog_deleting, on))
            }

            if (state.isRechecking) {
                ProgressNote(stringResource(Res.string.delete_remote_branches_dialog_rechecking, remote ?: copyRemotes))
            }

            if (error != null) {
                DialogWarning(error.getStyledErrorText())
            }
        }
    }
}

/** The remote that copies go to, or "their remotes" when they go to several. */
@Composable
private fun copiesRemoteName(remotes: List<String>): String {
    val distinct = remotes.distinct()

    return distinct.singleOrNull() ?: stringResource(Res.string.delete_branches_dialog_copies_their_remotes)
}

/** What the dialog's button can do about git not running: turn the setting on, or open Settings to set git's path. */
private enum class GitFix { USE_GIT, OPEN_SETTINGS }

private fun gitFixFor(reason: SshNeedsGitError.Reason): GitFix? = when (reason) {
    SshNeedsGitError.Reason.SettingOff -> GitFix.USE_GIT
    SshNeedsGitError.Reason.GitNotFound -> GitFix.OPEN_SETTINGS
    // A build without it can't be fixed from here; deleting branches pushes no LFS objects
    SshNeedsGitError.Reason.HelperMissing, SshNeedsGitError.Reason.LfsPushWithoutGitLfs -> null
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
        remote != null && result.wasStopped && deleted == 0 ->
            stringResource(Res.string.delete_remote_branches_dialog_result_stopped_none, remote)

        remote != null && result.wasStopped ->
            stringResource(Res.string.delete_remote_branches_dialog_result_stopped, deleted, total, remote)

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

/** While git deletes on the server, where Cancel is Stop, and while the server is asked what a stop left there. */
@Composable
private fun ProgressNote(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colors.primaryVariant,
        )

        Text(
            text = text,
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
    onDeletesCopiesChange: (Boolean) -> Unit,
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
                copy = plan.copies[info.branch.name].takeIf { state.deletesCopies },
                onCheckedChange = { onCheckedChange(info.branch.name, it) },
            )
        }
    }

    if (plan.offersCopies) {
        CopiesSwitch(state, onDeletesCopiesChange)
    }

    if (base == null) {
        ListNote(noBaseText(state.request.remote))
    }

    if (state.request.remote != null || state.copiesToDelete.isNotEmpty()) {
        ListNote(stringResource(Res.string.delete_remote_branches_dialog_lease))

        // Only merged copies go, so this is about a remote's own branches
        if (state.request.remote != null && state.checksUnmerged) {
            ListNote(stringResource(Res.string.delete_remote_branches_dialog_unmerged_warning), MaterialTheme.colors.amberText)
        }
    }
}

/**
 * "Also delete their copies on origin (2)": the checked branches' upstreams go too, but the remote's `HEAD` and default
 * branch. Off each time the dialog opens, as deleting on a server affects everyone.
 */
@Composable
private fun CopiesSwitch(state: DeleteBranchesState, onChange: (Boolean) -> Unit) {
    val offered = state.copiesOffered
    val isEnabled = !state.isDeleting

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.handMouseClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { if (isEnabled) onChange(!state.deletesCopies) },
        ),
    ) {
        Checkbox(
            checked = state.deletesCopies,
            onCheckedChange = onChange,
            enabled = isEnabled,
            modifier = Modifier
                .padding(all = 8.dp)
                .size(12.dp),
        )

        Text(
            text = stringResource(
                Res.string.delete_branches_dialog_copies_switch,
                copiesRemoteName(state.plan?.copies?.values?.filter { it.goes }?.map { it.remote }.orEmpty()),
                offered.size,
            ),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

/**
 * What happens to a branch's [copy] on its remote, while its copies are asked to go too: it goes, in amber when it isn't
 * merged or its name looks long-lived, or it stays when the server would refuse it.
 */
@Composable
private fun copyLine(copy: BranchCopy): Pair<String, Color> {
    val name = copy.info.branch.remoteAndName
    val kind = copy.kind

    return when {
        kind is BranchDeletionKind.Refused -> stringResource(
            Res.string.delete_branches_dialog_copy_stays,
            name,
            refusalText(kind.reason, copy.remote, copy.info.branch.shortTip),
        ) to MaterialTheme.colors.onBackgroundSecondary

        !copy.isRisky -> stringResource(Res.string.delete_branches_dialog_copy_goes, name) to
            MaterialTheme.colors.onBackgroundSecondary

        else -> stringResource(Res.string.delete_branches_dialog_copy_goes_risky, name, copyRisk(copy)) to
            MaterialTheme.colors.amberText
    }
}

/** Why deleting [copy] needs a look: not merged, where a server can close its pull requests, or a long-lived name. */
@Composable
internal fun copyRisk(copy: BranchCopy): String = if (copy.kind !is BranchDeletionKind.Merged) {
    stringResource(Res.string.delete_branches_dialog_copy_not_merged)
} else {
    stringResource(Res.string.delete_branches_dialog_copy_long_lived)
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
    copy: BranchCopy?,
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

            if (copy != null && kind !is BranchDeletionKind.Refused) {
                val (copyText, copyColor) = copyLine(copy)

                Text(text = copyText, style = MaterialTheme.typography.caption, color = copyColor)
            }
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
        is BranchKept.ChangedOnRemote -> reason.shortNow?.let {
            stringResource(Res.string.delete_remote_branches_dialog_result_changed_now, remoteName, it)
        } ?: stringResource(Res.string.delete_remote_branches_dialog_result_changed, remoteName)
        is BranchKept.RefusedByRemote ->
            stringResource(Res.string.delete_remote_branches_dialog_result_refused, remoteName, reason.reason)

        BranchKept.StillOnRemote -> stringResource(Res.string.delete_remote_branches_dialog_result_still_there, remoteName)

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
private fun ResultDetails(
    result: BranchesDeletion,
    remote: String?,
    copiesAsked: Map<String, BranchCopy>,
    canDeleteAnyway: Boolean,
    onDeleteAnyway: () -> Unit,
) {
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
                    extra = copiesAsked[branch.name]?.let { copyResultLine(it, result.copies[branch.name], result) },
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

    CopiesNotes(result, copiesAsked)

    // Someone pushed to some since the fetch: the user decides, now that the result says what the server has
    val anyway = (if (remote != null) result.changedOnRemote.size else 0) + result.changedCopies.size

    if (anyway > 0 && canDeleteAnyway) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryButton(
                text = pluralStringResource(Res.plurals.delete_branches_dialog_delete_anyway, anyway, anyway),
                colors = MaterialTheme.styleColors.destructive,
                onClick = onDeleteAnyway,
            )
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

    // And a copy, on its remote
    for (copyRemote in result.deletedCopies.map { it.remoteAndName.substringBefore('/') }.distinct()) {
        ListNote(stringResource(Res.string.delete_remote_branches_dialog_result_restore, copyRemote))
    }

    val remoteMessage = result.remoteMessage

    // What the server said: a remote's, or the one that their copies went to
    val saidBy = remote ?: copiesAsked.values.map { it.remote }.distinct().singleOrNull()

    if (saidBy != null && remoteMessage != null) {
        ListNote(stringResource(Res.string.delete_remote_branches_dialog_result_server_said, saidBy))

        Text(
            text = remoteMessage,
            style = MaterialTheme.typography.caption,
            fontFamily = monoTypography(),
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** What happened to a deleted branch's [copy]: its [outcome] on its remote, or why it's not known. */
@Composable
private fun copyResultLine(copy: BranchCopy, outcome: BranchDeletionOutcome?, result: BranchesDeletion): Pair<String, Color> {
    val name = copy.info.branch.remoteAndName
    val amber = MaterialTheme.colors.amberText

    return when (outcome) {
        is BranchDeletionOutcome.Deleted -> stringResource(Res.string.delete_branches_dialog_result_copy_deleted, name) to
            MaterialTheme.colors.onBackgroundSecondary

        is BranchDeletionOutcome.Kept -> stringResource(
            Res.string.delete_branches_dialog_copy_stays,
            name,
            refusalText(outcome.reason, copy.remote, copy.info.branch.shortTip),
        ) to amber

        null -> if (result.wasStopped) {
            stringResource(Res.string.delete_branches_dialog_result_copy_unknown, name) to amber
        } else {
            stringResource(Res.string.delete_branches_dialog_result_copy_not_deleted, name) to amber
        }
    }
}

/**
 * Under the result: why a remote's copies weren't deleted at all, or for a stop that the server couldn't be asked
 * about, that some may be gone.
 */
@Composable
private fun CopiesNotes(result: BranchesDeletion, copiesAsked: Map<String, BranchCopy>) {
    val deleted = result.deleted.map { it.name }.toSet()
    val unknown = copiesAsked.filterKeys { it in deleted && it !in result.copies }.values.map { it.remote }.distinct()

    for (remote in unknown) {
        val error = result.copyErrors[remote]

        if (result.wasStopped) {
            DialogWarning(stringResource(Res.string.delete_branches_dialog_result_copies_stopped, remote))
        } else {
            ListNote(stringResource(Res.string.delete_branches_dialog_result_copies_failed, remote), MaterialTheme.colors.amberText)
        }

        if (error != null) {
            DialogWarning(error.getStyledErrorText())
        }
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
    extra: Pair<String, Color>? = null,
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

            if (extra != null) {
                Text(text = extra.first, style = MaterialTheme.typography.caption, color = extra.second)
            }
        }
    }
}
