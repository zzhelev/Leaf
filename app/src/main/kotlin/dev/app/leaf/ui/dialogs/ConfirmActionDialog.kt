// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.theme.diffLineAdded
import dev.app.leaf.theme.diffLineRemoved
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 360.dp

/** An action that can lose work, and what its confirmation dialog needs to say about it. */
sealed interface ConfirmableAction {
    data class DeleteSubmodule(val path: String) : ConfirmableAction

    data class DeleteFile(val entry: StatusEntry) : ConfirmableAction

    /** Aborts [operation]. [changedFiles] files have uncommitted changes, which the abort resets. */
    data class AbortOperation(val operation: Operation, val changedFiles: Int) : ConfirmableAction {
        enum class Operation { MERGE, REBASE, CHERRY_PICK, REVERT }
    }

    data object SkipRebaseCommit : ConfirmableAction

    data class DropStash(val stash: Commit) : ConfirmableAction

    data class DeleteRemoteBranch(val branch: Branch) : ConfirmableAction

    data class ForcePush(val branchName: String) : ConfirmableAction

    data class DeleteRemote(val remoteName: String) : ConfirmableAction

    /** Discards the unstaged [hunk] of [filePath]. */
    data class DiscardHunk(val filePath: String, val hunk: Hunk) : ConfirmableAction

    /** Discards the unstaged [line] of [filePath]: removes it when it was added, puts it back when it was removed. */
    data class DiscardLine(val filePath: String, val line: Line) : ConfirmableAction

    /** Discards the changes of [entry]. In the Staged list, that's its staged and unstaged changes. */
    data class DiscardFile(val entry: StatusEntry) : ConfirmableAction

    /** Discards the changes of [count] selected files of the [entryType] list. */
    data class DiscardFiles(val count: Int, val entryType: EntryType) : ConfirmableAction
}

/** Code that the dialog shows as it is, such as the line that's discarded. */
private class CodeSnippet(val text: String, val background: Color)

private class ConfirmationTexts(
    val icon: Painter,
    val title: String,
    val subtitle: String,
    val warning: String?,
    val primaryAction: String,
    val code: CodeSnippet? = null,
    /** The label of the "Don't ask again" box, for actions whose confirmation can be turned off. */
    val stopAsking: String? = null,
)

/**
 * Asks before [action]. [onConfirm] runs it.
 *
 * With [onStopAsking], an action whose confirmation can be turned off gets a "Don't ask again" box. When it's checked,
 * confirming calls [onStopAsking] before [onConfirm]. Cancelling leaves the setting as it is.
 */
@Composable
fun ConfirmActionDialog(
    action: ConfirmableAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onStopAsking: (() -> Unit)? = null,
) {
    val texts = action.texts()
    val stopAskingLabel = texts.stopAsking?.takeIf { onStopAsking != null }
    var stopAsking by remember { mutableStateOf(false) }

    IconBasedDialog(
        icon = texts.icon,
        title = texts.title,
        subtitle = texts.subtitle,
        primaryActionText = texts.primaryAction,
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            if (stopAskingLabel != null && stopAsking) {
                onStopAsking?.invoke()
            }

            onConfirm()
        },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps long messages on fewer lines
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (texts.code != null) {
                DialogCode(texts.code)
            }

            if (texts.warning != null) {
                DialogWarning(texts.warning)
            }

            if (stopAskingLabel != null) {
                CheckboxText(
                    value = stopAsking,
                    onCheckedChange = { stopAsking = !stopAsking },
                    text = stopAskingLabel,
                )
            }
        }
    }
}

/** [code] in the monospace font, at most three lines. It can be selected and copied. */
@Composable
private fun DialogCode(code: CodeSnippet) {
    SelectionContainer {
        Text(
            text = code.text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(code.background)
                .padding(vertical = 4.dp, horizontal = 8.dp),
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.body2,
            fontFamily = monoTypography(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A message about work that the action loses. Its text can be selected and copied. */
@Composable
fun DialogWarning(text: String) {
    DialogWarning(AnnotatedString(text))
}

/**
 * A message about work that the action loses, or about why it can't be done. Its text can be selected and copied, for
 * example a folder it names.
 */
@Composable
fun DialogWarning(text: AnnotatedString) {
    // The theme's selection color would hardly show on the error color
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colors.onError,
        backgroundColor = Color.Black.copy(alpha = 0.35f),
    )

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colors.error)
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                color = MaterialTheme.colors.onError,
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
private fun ConfirmableAction.texts(): ConfirmationTexts {
    val delete = painterResource(Res.drawable.delete)
    val warning = painterResource(Res.drawable.warning)
    val undo = painterResource(Res.drawable.undo)

    return when (this) {
        is ConfirmableAction.DeleteSubmodule -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_submodule_dialog_title),
            subtitle = stringResource(Res.string.delete_submodule_dialog_subtitle, path),
            warning = stringResource(Res.string.delete_submodule_dialog_warning),
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.DeleteFile -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_file_dialog_title),
            subtitle = stringResource(Res.string.delete_file_dialog_subtitle, entry.filePath),
            // Unstaged ADDED entries are the untracked files
            warning = if (entry.statusType == StatusType.ADDED) {
                stringResource(Res.string.delete_file_dialog_warning_untracked)
            } else {
                stringResource(Res.string.delete_file_dialog_warning_tracked)
            },
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.AbortOperation -> {
            val (title, subtitle) = when (operation) {
                ConfirmableAction.AbortOperation.Operation.MERGE ->
                    Res.string.abort_dialog_title_merge to Res.string.abort_dialog_subtitle_merge

                ConfirmableAction.AbortOperation.Operation.REBASE ->
                    Res.string.abort_dialog_title_rebase to Res.string.abort_dialog_subtitle_rebase

                ConfirmableAction.AbortOperation.Operation.CHERRY_PICK ->
                    Res.string.abort_dialog_title_cherry_pick to Res.string.abort_dialog_subtitle_cherry_pick

                ConfirmableAction.AbortOperation.Operation.REVERT ->
                    Res.string.abort_dialog_title_revert to Res.string.abort_dialog_subtitle_revert
            }

            ConfirmationTexts(
                icon = warning,
                title = stringResource(title),
                subtitle = stringResource(subtitle),
                warning = if (changedFiles > 0) {
                    pluralStringResource(Res.plurals.abort_dialog_changes_discarded, changedFiles, changedFiles)
                } else {
                    null
                },
                primaryAction = stringResource(Res.string.abort_dialog_abort),
            )
        }

        ConfirmableAction.SkipRebaseCommit -> ConfirmationTexts(
            icon = warning,
            title = stringResource(Res.string.skip_rebase_dialog_title),
            subtitle = stringResource(Res.string.skip_rebase_dialog_subtitle),
            warning = null,
            primaryAction = stringResource(Res.string.skip_rebase_dialog_skip),
        )

        is ConfirmableAction.DropStash -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.drop_stash_dialog_title),
            subtitle = stringResource(Res.string.drop_stash_dialog_subtitle, stash.shortMessage),
            warning = stringResource(Res.string.drop_stash_dialog_warning),
            primaryAction = stringResource(Res.string.drop_stash_dialog_drop),
        )

        is ConfirmableAction.DeleteRemoteBranch -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_remote_branch_dialog_title),
            subtitle = stringResource(
                Res.string.delete_remote_branch_dialog_subtitle,
                branch.simpleName,
                branch.remoteName,
            ),
            warning = stringResource(Res.string.delete_remote_branch_dialog_warning),
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.ForcePush -> ConfirmationTexts(
            icon = warning,
            title = stringResource(Res.string.force_push_dialog_title),
            subtitle = stringResource(Res.string.force_push_dialog_subtitle, branchName),
            warning = stringResource(Res.string.force_push_dialog_warning, branchName),
            primaryAction = stringResource(Res.string.force_push_dialog_push),
        )

        is ConfirmableAction.DeleteRemote -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_remote_dialog_title),
            subtitle = stringResource(Res.string.delete_remote_dialog_subtitle, remoteName),
            warning = null,
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.DiscardHunk -> {
            val addedLines = hunk.lines.count { it.lineType == LineType.ADDED }

            ConfirmationTexts(
                icon = undo,
                title = stringResource(Res.string.discard_hunk_dialog_title),
                subtitle = stringResource(Res.string.discard_hunk_dialog_subtitle, filePath),
                // A hunk that only removes lines puts them back, which loses nothing
                warning = if (addedLines > 0) {
                    pluralStringResource(Res.plurals.discard_hunk_dialog_warning, addedLines, addedLines)
                } else {
                    null
                },
                primaryAction = stringResource(Res.string.confirm_dialog_discard),
                code = CodeSnippet(hunk.header.trim(), MaterialTheme.colors.secondarySurface),
                stopAsking = stringResource(Res.string.confirm_dialog_stop_asking_hunks_and_lines),
            )
        }

        is ConfirmableAction.DiscardLine -> {
            val isAdded = line.lineType == LineType.ADDED

            ConfirmationTexts(
                icon = undo,
                title = stringResource(Res.string.discard_line_dialog_title),
                subtitle = if (isAdded) {
                    stringResource(Res.string.discard_line_dialog_subtitle_added, filePath)
                } else {
                    stringResource(Res.string.discard_line_dialog_subtitle_removed, filePath)
                },
                // Putting a removed line back loses nothing
                warning = if (isAdded) stringResource(Res.string.discard_line_dialog_warning_added) else null,
                primaryAction = stringResource(Res.string.confirm_dialog_discard),
                code = CodeSnippet(
                    text = (if (isAdded) "+ " else "- ") + line.text.trim(),
                    background = if (isAdded) MaterialTheme.colors.diffLineAdded else MaterialTheme.colors.diffLineRemoved,
                ),
                stopAsking = stringResource(Res.string.confirm_dialog_stop_asking_hunks_and_lines),
            )
        }

        // DiscardEntriesGitAction resets a staged file in the index and then checks it out
        is ConfirmableAction.DiscardFile -> ConfirmationTexts(
            icon = undo,
            title = stringResource(Res.string.discard_file_dialog_title),
            subtitle = when (entry.entryType) {
                EntryType.STAGED -> stringResource(Res.string.discard_file_dialog_subtitle_staged, entry.filePath)
                EntryType.UNSTAGED -> stringResource(Res.string.discard_file_dialog_subtitle_unstaged, entry.filePath)
            },
            warning = when {
                entry.entryType == EntryType.STAGED -> stringResource(Res.string.discard_file_dialog_warning_staged)
                // Discarding the deletion of a file brings it back, which loses nothing
                entry.statusType == StatusType.REMOVED -> null
                else -> stringResource(Res.string.discard_changes_dialog_warning)
            },
            primaryAction = stringResource(Res.string.confirm_dialog_discard),
        )

        is ConfirmableAction.DiscardFiles -> ConfirmationTexts(
            icon = undo,
            title = stringResource(Res.string.discard_files_dialog_title),
            subtitle = when (entryType) {
                EntryType.STAGED -> pluralStringResource(Res.plurals.discard_files_dialog_subtitle_staged, count, count)
                EntryType.UNSTAGED ->
                    pluralStringResource(Res.plurals.discard_files_dialog_subtitle_unstaged, count, count)
            },
            warning = when (entryType) {
                EntryType.STAGED -> stringResource(Res.string.discard_files_dialog_warning_staged)
                EntryType.UNSTAGED -> stringResource(Res.string.discard_changes_dialog_warning)
            },
            primaryAction = stringResource(Res.string.confirm_dialog_discard),
        )
    }
}
