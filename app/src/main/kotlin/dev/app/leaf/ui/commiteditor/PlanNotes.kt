// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.commiteditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.rewrite.PlanFooter
import dev.app.leaf.domain.rewrite.PlanNote
import dev.app.leaf.domain.rewrite.PlanRefusal
import dev.app.leaf.domain.rewrite.PlanStart
import dev.app.leaf.domain.rewrite.shortName
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.amberText
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** How much of a commit's subject a note names, as the footer is one line a note. */
private const val NOTE_SUBJECT_LENGTH = 40

/** How many files a note names before "and N more". */
private const val NOTE_FILES_NAMED = 3

/**
 * The footer's notes (fork-only, B5 of `docs/fork/commit-editor.md`), one line each: amber for what stops the plan or
 * Start, the others muted. Only what is to be acted on or known before Start.
 */
@Composable
internal fun PlanNotes(notes: List<PlanNote>, branch: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (note in notes) {
            Text(
                text = planNoteText(note, branch),
                style = MaterialTheme.typography.body2,
                color = if (note.isWarning) MaterialTheme.colors.amberText else MaterialTheme.colors.onBackgroundSecondary,
            )
        }
    }
}

/** Whether a note is in amber: a stop, or something that keeps Start disabled or changes what others have pushed. */
internal val PlanNote.isWarning: Boolean
    get() = blocks || this is PlanNote.StopsAtConflict || this is PlanNote.MayStop || this is PlanNote.Pushed ||
        this is PlanNote.RecentChange

/** What a note says. [branch] is the plan's branch, by its short name. */
@Composable
internal fun planNoteText(note: PlanNote, branch: String): String = when (note) {
    is PlanNote.StopsAtConflict -> {
        val subject = cutSubject(note.commit.shortMessage)
        val file = note.file.substringAfterLast('/')

        if (note.more > 0) {
            pluralStringResource(
                Res.plurals.commit_editor_note_stops_more,
                note.more,
                note.commit.shortHash,
                subject,
                file,
                note.more,
            )
        } else {
            stringResource(Res.string.commit_editor_note_stops, note.commit.shortHash, subject, file)
        }
    }

    is PlanNote.StopsToEdit -> stringResource(Res.string.commit_editor_note_stops_to_edit, note.commit.shortHash)
    is PlanNote.MayStop -> stringResource(Res.string.commit_editor_note_may_stop)
    is PlanNote.Refused -> when (val refusal = note.refusal) {
        is PlanRefusal.JoinsMerge -> stringResource(Res.string.commit_editor_note_joins_merge, refusal.merge.take(7))
        is PlanRefusal.ReplaysMerge -> stringResource(Res.string.commit_editor_note_replays_merge, refusal.merge.take(7))
        is PlanRefusal.MergeInRebase -> stringResource(Res.string.commit_editor_note_replays_merge, refusal.merge.take(7))
    }

    is PlanNote.RunsIn -> stringResource(Res.string.commit_editor_note_runs_in, note.worktreeName)
    is PlanNote.ChecksOutHere -> stringResource(Res.string.commit_editor_note_checks_out_here, note.branch)
    is PlanNote.ChangesThere ->
        pluralStringResource(Res.plurals.commit_editor_note_changes_there, note.count, note.worktreeName, note.count)

    is PlanNote.RecentChange -> {
        val minutes = (System.currentTimeMillis() - note.at).coerceAtLeast(0).milliseconds.inWholeMinutes.toInt()

        if (minutes == 0) {
            stringResource(Res.string.worktree_dialog_recent_change_now, note.worktreeName)
        } else {
            pluralStringResource(Res.plurals.worktree_dialog_recent_change, minutes, note.worktreeName, minutes)
        }
    }

    is PlanNote.FilesInTheWay -> stringResource(Res.string.commit_editor_note_in_the_way, fileNames(note.files))
    is PlanNote.Pushed -> pluralStringResource(Res.plurals.rewrite_dialog_pushed, note.count, note.count, note.upstream)
    PlanNote.BranchMoved -> stringResource(Res.string.commit_editor_note_branch_moved, branch)
    PlanNote.EmptyMessage -> stringResource(Res.string.commit_editor_note_empty_message)
}

/** "7 commits → 5", then "1 joined · 1 dropped · reordered · 1 stop". */
@Composable
internal fun planSummary(footer: PlanFooter): Pair<String, String> {
    val counts = pluralStringResource(Res.plurals.commit_editor_summary, footer.before, footer.before, footer.after)
    val tags = buildList {
        if (footer.joined > 0) add(stringResource(Res.string.commit_editor_summary_joined, footer.joined))
        if (footer.dropped > 0) add(stringResource(Res.string.commit_editor_summary_dropped, footer.dropped))
        if (footer.isReordered) add(stringResource(Res.string.commit_editor_summary_reordered))
        if (footer.edits > 0) add(pluralStringResource(Res.plurals.commit_editor_summary_stops, footer.edits, footer.edits))
    }

    return counts to tags.joinToString(" · ")
}

/** Start's label: "Rewrite 7 commits", or "Start rebase" for a plan that stops. */
@Composable
internal fun startLabel(footer: PlanFooter): String = when (footer.start) {
    PlanStart.REWRITE -> pluralStringResource(Res.plurals.commit_editor_rewrite, footer.before, footer.before)
    PlanStart.REBASE -> stringResource(Res.string.commit_editor_start_rebase)
}

/** The box that sets the tab's uncommitted changes aside while the rebase runs (choice 3a), when it shows. */
@Composable
internal fun AutostashBox(count: Int, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = isChecked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = null,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(12.dp),
        )

        Text(
            text = pluralStringResource(Res.plurals.commit_editor_autostash, count, count),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

/** The files' names, the first [NOTE_FILES_NAMED], then how many more. */
@Composable
private fun fileNames(files: List<String>): String {
    val names = files.take(NOTE_FILES_NAMED).joinToString(", ") { it.substringAfterLast('/') }
    val more = files.size - NOTE_FILES_NAMED

    return if (more > 0) pluralStringResource(Res.plurals.rewrite_dialog_and_more_files, more, names, more) else names
}

/** [subject], cut with "…" past [NOTE_SUBJECT_LENGTH] characters. */
internal fun cutSubject(subject: String): String =
    if (subject.length <= NOTE_SUBJECT_LENGTH) subject else subject.take(NOTE_SUBJECT_LENGTH - 1).trimEnd() + "…"

/** The plan's branch as the footer names it. */
internal fun branchName(branch: String): String = shortName(branch)
