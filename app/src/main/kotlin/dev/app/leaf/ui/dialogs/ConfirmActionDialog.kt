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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.restore.CurrentFile
import dev.app.leaf.domain.restore.FileRestorePlan
import dev.app.leaf.domain.sorting.DiscardAllPlan
import dev.app.leaf.theme.diffLineAdded
import dev.app.leaf.theme.diffLineRemoved
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getErrorText
import dev.app.leaf.ui.status.named
import dev.app.leaf.ui.status.other
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
        /** Fork-only: [CHERRY_PICK_SEQUENCE] is a cherry-pick of several commits, which git's abort undoes whole. */
        enum class Operation { MERGE, REBASE, CHERRY_PICK, REVERT, SQUASH, CHERRY_PICK_SEQUENCE }
    }

    /**
     * Fork-only: leaves out the commit that a cherry-pick of several commits stopped at, and picks the rest. Its changes
     * in [discardedFiles] files, staged or with conflicts, are lost.
     */
    data class SkipCherryPickCommit(val discardedFiles: Int) : ConfirmableAction

    /**
     * Leaves out the commit that a rebase stopped at. Fork-only: [commit] (short), [subject] and [branch] name it when
     * known, and its changes in [discardedFiles] files, staged or with conflicts, are lost.
     */
    data class SkipRebaseCommit(
        val commit: String? = null,
        val subject: String? = null,
        val branch: String? = null,
        val discardedFiles: Int = 0,
    ) : ConfirmableAction

    data class DropStash(val stash: Commit) : ConfirmableAction

    data class DeleteRemoteBranch(val branch: Branch) : ConfirmableAction

    data class ForcePush(val branchName: String) : ConfirmableAction

    data class DeleteRemote(val remoteName: String) : ConfirmableAction

    /** Deletes the file of the user's style named [styleName] (Settings → Appearance → Style). */
    data class DeleteStyle(val styleName: String) : ConfirmableAction

    /** Closes the style editor without saving the changes to [styleName]. */
    data class DiscardStyleChanges(val styleName: String) : ConfirmableAction

    /** Discards the unstaged [hunk] of [filePath]. */
    data class DiscardHunk(val filePath: String, val hunk: Hunk) : ConfirmableAction

    /** Discards the unstaged [line] of [filePath]: removes it when it was added, puts it back when it was removed. */
    data class DiscardLine(val filePath: String, val line: Line) : ConfirmableAction

    /** Discards the changes of [entry]. In the Staged list, that's its staged and unstaged changes. */
    data class DiscardFile(val entry: StatusEntry) : ConfirmableAction

    /**
     * Discards the changes of [count] selected files of the [entryType] list. [keptConflicted] more selected files have
     * conflicts, and are left as they are.
     */
    data class DiscardFiles(val count: Int, val entryType: EntryType, val keptConflicted: Int = 0) : ConfirmableAction

    /**
     * Discards the unstaged changes of the files that [plan] lists ("Discard all", fork-only): the pane's files, or
     * only its [search] matches while it searches. The new files go only with [deleteNewFiles] ticked, which the
     * caller reads when the dialog confirms.
     */
    data class DiscardAll(
        val plan: DiscardAllPlan,
        val search: String?,
        val deleteNewFiles: ConfirmOption = ConfirmOption(),
    ) : ConfirmableAction

    /**
     * Writes [filePath] as [commit] had it into the working tree ("Restore this version of the file" in file history,
     * fork-only). [plan] says what the file is now, which is replaced, or why it can't be restored.
     */
    data class RestoreFileVersion(val filePath: String, val commit: Commit, val plan: FileRestorePlan) : ConfirmableAction

    /**
     * Resolves the conflicts of [entries] with [side]'s version, which loses the other side's changes and any edits
     * made to the files. [sides] names the two sides, as few people remember which is ours.
     */
    data class UseConflictSide(
        val entries: List<StatusEntry>,
        val side: ConflictSide,
        val sides: ConflictSides?,
    ) : ConfirmableAction
}

/** Code that the dialog shows as it is, such as the line that's discarded. */
private class CodeSnippet(val text: String, val background: Color)

/** A box that the user ticks to let an action do something more. It is unticked every time the dialog opens. */
class ConfirmOption {
    var isChecked by mutableStateOf(false)
}

/** The box of [option], with its [label] and a [hint] under it. */
private class OptionTexts(val label: String, val hint: String?, val option: ConfirmOption)

private class ConfirmationTexts(
    val icon: Painter,
    val title: String,
    val subtitle: String,
    val warning: String?,
    val primaryAction: String,
    val code: CodeSnippet? = null,
    /** What the action leaves alone, such as selected files with conflicts. */
    val note: String? = null,
    /** The names of the files that [note] counts, in the monospace font (fork-only). */
    val noteFiles: String? = null,
    /** Another thing that the action leaves alone, after [noteFiles] (fork-only). */
    val secondNote: String? = null,
    /** A box for something more that the action may do (fork-only). */
    val option: OptionTexts? = null,
    /** False while the action would do nothing, which the box may change (fork-only). */
    val isPrimaryEnabled: Boolean = true,
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
        isPrimaryActionEnabled = texts.isPrimaryEnabled,
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

            if (texts.note != null) {
                KeptFilesNote(texts.note, Modifier.fillMaxWidth())
            }

            if (texts.noteFiles != null) {
                Text(
                    text = texts.noteFiles,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    style = MaterialTheme.typography.body2,
                    fontFamily = monoTypography(),
                    textAlign = TextAlign.Center,
                )
            }

            if (texts.secondNote != null) {
                KeptFilesNote(texts.secondNote, Modifier.fillMaxWidth())
            }

            if (texts.option != null) {
                OptionBox(texts.option)
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

/** The box of an option, with the hint that says what ticking it costs. */
@Composable
private fun OptionBox(texts: OptionTexts) {
    Column {
        CheckboxText(
            value = texts.option.isChecked,
            onCheckedChange = { texts.option.isChecked = !texts.option.isChecked },
            text = texts.label,
        )

        if (texts.hint != null) {
            Text(
                text = texts.hint,
                modifier = Modifier.padding(start = 32.dp),
                color = MaterialTheme.colors.onBackgroundSecondary,
                style = MaterialTheme.typography.caption,
            )
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
        is ConfirmableAction.UseConflictSide -> {
            val count = entries.size
            val isOurs = side == ConflictSide.OURS
            val chosen = side.named(sides)
            val lost = pluralStringResource(Res.plurals.use_side_dialog_warning, count, count, side.other.named(sides))
            // Where the chosen side deleted the file, it's deleted
            val deleted = entries.count { it.conflict?.version(side) == null }

            ConfirmationTexts(
                icon = painterResource(if (isOurs) Res.drawable.arrow_back else Res.drawable.arrow_forward),
                title = stringResource(if (isOurs) Res.string.use_ours_dialog_title else Res.string.use_theirs_dialog_title),
                subtitle = if (count == 1) {
                    stringResource(Res.string.use_side_dialog_subtitle_file, entries.single().filePath, chosen)
                } else {
                    pluralStringResource(Res.plurals.use_side_dialog_subtitle_files, count, count, chosen)
                },
                warning = when {
                    deleted == 0 -> lost
                    count == 1 -> lost + " " + stringResource(Res.string.use_side_dialog_deleted_file, chosen)
                    else -> lost + " " + pluralStringResource(Res.plurals.use_side_dialog_deleted, deleted, deleted, chosen)
                },
                primaryAction = stringResource(Res.string.use_side_dialog_resolve),
            )
        }

        is ConfirmableAction.DeleteSubmodule -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_submodule_dialog_title),
            subtitle = stringResource(Res.string.delete_submodule_dialog_subtitle, path),
            warning = stringResource(Res.string.delete_submodule_dialog_warning),
            primaryAction = stringResource(Res.string.confirm_dialog_delete),
        )

        is ConfirmableAction.DeleteFile -> ConfirmationTexts(
            icon = delete,
            title = stringResource(
                if (entry.nestedRepository != null) Res.string.delete_repository_dialog_title
                else Res.string.delete_file_dialog_title,
            ),
            subtitle = stringResource(Res.string.delete_file_dialog_subtitle, entry.filePath),
            // Unstaged ADDED entries are the untracked files. A nested repository is a folder (fork-only)
            warning = when {
                entry.nestedRepository != null -> stringResource(Res.string.delete_repository_dialog_warning)
                entry.statusType == StatusType.ADDED -> stringResource(Res.string.delete_file_dialog_warning_untracked)
                else -> stringResource(Res.string.delete_file_dialog_warning_tracked)
            },
            primaryAction = stringResource(
                if (entry.nestedRepository != null) Res.string.delete_repository_dialog_primary
                else Res.string.confirm_dialog_delete,
            ),
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

                ConfirmableAction.AbortOperation.Operation.SQUASH ->
                    Res.string.abort_dialog_title_squash to Res.string.abort_dialog_subtitle_squash

                ConfirmableAction.AbortOperation.Operation.CHERRY_PICK_SEQUENCE ->
                    Res.string.abort_dialog_title_cherry_pick to Res.string.abort_dialog_subtitle_cherry_pick_sequence
            }

            ConfirmationTexts(
                icon = warning,
                title = stringResource(title),
                subtitle = stringResource(subtitle),
                // Fork-only: `git reset --merge` keeps unstaged changes, unlike the others' hard reset
                warning = if (operation == ConfirmableAction.AbortOperation.Operation.SQUASH) {
                    stringResource(Res.string.abort_dialog_squash_warning)
                } else if (operation == ConfirmableAction.AbortOperation.Operation.CHERRY_PICK_SEQUENCE) {
                    // Fork-only: git's abort is a `reset --merge` too
                    stringResource(Res.string.abort_dialog_cherry_pick_sequence_warning)
                } else if (changedFiles > 0) {
                    pluralStringResource(Res.plurals.abort_dialog_changes_discarded, changedFiles, changedFiles)
                } else {
                    null
                },
                primaryAction = stringResource(Res.string.abort_dialog_abort),
            )
        }

        is ConfirmableAction.SkipCherryPickCommit -> ConfirmationTexts(
            icon = warning,
            title = stringResource(Res.string.skip_rebase_dialog_title),
            subtitle = stringResource(Res.string.skip_cherry_pick_dialog_subtitle),
            warning = if (discardedFiles > 0) {
                pluralStringResource(Res.plurals.skip_cherry_pick_dialog_warning, discardedFiles, discardedFiles)
            } else {
                null
            },
            primaryAction = stringResource(Res.string.skip_rebase_dialog_skip),
        )

        is ConfirmableAction.SkipRebaseCommit -> ConfirmationTexts(
            icon = warning,
            title = stringResource(Res.string.skip_rebase_dialog_title),
            // Fork-only: names the commit and the branch it's left out of
            subtitle = when {
                commit == null || subject == null -> stringResource(Res.string.skip_rebase_dialog_subtitle)
                branch == null -> stringResource(Res.string.skip_rebase_dialog_subtitle_commit, commit, subject)
                else -> stringResource(Res.string.skip_rebase_dialog_subtitle_commit_branch, commit, subject, branch)
            },
            warning = if (discardedFiles > 0) {
                pluralStringResource(Res.plurals.skip_rebase_dialog_warning, discardedFiles, discardedFiles)
            } else {
                null
            },
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

        is ConfirmableAction.DeleteStyle -> ConfirmationTexts(
            icon = delete,
            title = stringResource(Res.string.delete_style_dialog_title),
            subtitle = stringResource(Res.string.delete_style_dialog_subtitle, styleName),
            warning = stringResource(Res.string.delete_style_dialog_warning),
            primaryAction = stringResource(Res.string.delete_style_dialog_delete),
        )

        is ConfirmableAction.DiscardStyleChanges -> ConfirmationTexts(
            icon = warning,
            title = stringResource(Res.string.discard_style_changes_dialog_title),
            subtitle = stringResource(Res.string.discard_style_changes_dialog_subtitle, styleName),
            warning = null,
            primaryAction = stringResource(Res.string.discard_style_changes_dialog_discard),
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
            note = if (keptConflicted > 0) {
                pluralStringResource(Res.plurals.discard_dialog_conflicted_files_kept, keptConflicted, keptConflicted)
            } else {
                null
            },
        )

        is ConfirmableAction.RestoreFileVersion -> {
            val refusal = plan.refusal

            ConfirmationTexts(
                icon = undo,
                title = stringResource(Res.string.restore_file_dialog_title),
                subtitle = stringResource(
                    Res.string.restore_file_dialog_subtitle,
                    filePath,
                    commit.shortHash,
                    commit.shortMessage.truncated(MAX_SUBJECT_LENGTH),
                ),
                warning = when {
                    refusal != null -> refusal.getErrorText()
                    plan.current == CurrentFile.UNSTAGED_CHANGES -> stringResource(Res.string.restore_file_dialog_warning_unstaged)
                    plan.current == CurrentFile.UNTRACKED -> stringResource(Res.string.restore_file_dialog_warning_untracked)
                    plan.current == CurrentFile.IGNORED -> stringResource(Res.string.restore_file_dialog_warning_ignored)
                    else -> null
                },
                primaryAction = stringResource(Res.string.restore_file_dialog_restore),
                note = if (refusal == null) stringResource(Res.string.restore_file_dialog_note) else null,
                secondNote = when {
                    refusal != null -> null
                    plan.current == CurrentFile.MISSING -> stringResource(Res.string.restore_file_dialog_missing_note)
                    plan.hasStagedChanges -> stringResource(Res.string.restore_file_dialog_staged_note)
                    else -> null
                },
                isPrimaryEnabled = refusal == null,
            )
        }

        is ConfirmableAction.DiscardAll -> {
            val restored = plan.restored.size
            val newFiles = plan.newFiles.size
            val kept = plan.keptConflicted.map { it.filePath }
            val deleting = newFiles > 0 && deleteNewFiles.isChecked

            ConfirmationTexts(
                icon = undo,
                title = stringResource(Res.string.discard_all_dialog_title),
                subtitle = when {
                    plan.restoresNothing -> stringResource(Res.string.discard_all_dialog_nothing_to_restore)
                    search != null ->
                        pluralStringResource(Res.plurals.discard_all_dialog_subtitle_matching, restored, restored, search)

                    else -> pluralStringResource(Res.plurals.discard_files_dialog_subtitle_unstaged, restored, restored)
                },
                warning = when {
                    deleting && !plan.restoresNothing ->
                        pluralStringResource(Res.plurals.discard_all_dialog_warning_with_new, newFiles, newFiles)

                    deleting -> pluralStringResource(Res.plurals.discard_all_dialog_warning_only_new, newFiles, newFiles)
                    plan.restoresNothing -> null
                    else -> stringResource(Res.string.discard_changes_dialog_warning)
                },
                primaryAction = if (deleting) {
                    stringResource(Res.string.discard_all_dialog_discard_and_delete)
                } else {
                    stringResource(Res.string.confirm_dialog_discard)
                },
                note = if (kept.isNotEmpty()) {
                    pluralStringResource(Res.plurals.discard_dialog_conflicted_files_kept, kept.size, kept.size)
                } else {
                    null
                },
                noteFiles = if (kept.isNotEmpty()) {
                    val more = kept.size - MAX_NAMED_FILES

                    kept.take(MAX_NAMED_FILES).joinToString(", ") +
                            if (more > 0) " " + stringResource(Res.string.discard_all_dialog_and_more, more) else ""
                } else {
                    null
                },
                secondNote = if (plan.keptSubmodules.isNotEmpty()) {
                    val submodules = plan.keptSubmodules.size

                    pluralStringResource(Res.plurals.discard_all_dialog_submodules_kept, submodules, submodules)
                } else {
                    null
                },
                option = if (newFiles > 0) {
                    OptionTexts(
                        label = pluralStringResource(Res.plurals.discard_all_dialog_delete_new_files, newFiles, newFiles),
                        hint = stringResource(Res.string.discard_all_dialog_new_files_hint),
                        option = deleteNewFiles,
                    )
                } else {
                    null
                },
                // Without tracked changes, the box is the only thing that makes it do something
                isPrimaryEnabled = !plan.restoresNothing || deleting,
            )
        }
    }
}

/** How many files with conflicts "Discard all" names before "and N more". */
private const val MAX_NAMED_FILES = 5

/** How long a commit's subject may be in the restore dialog's subtitle. */
private const val MAX_SUBJECT_LENGTH = 60

private fun String.truncated(length: Int) = if (this.length <= length) this else take(length - 1).trimEnd() + "…"
