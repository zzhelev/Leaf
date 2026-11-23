// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.conflicts.EditorSyntax
import dev.app.leaf.domain.editing.EditedFile
import dev.app.leaf.domain.editing.HunkEdit
import dev.app.leaf.domain.editing.changedTypedLines
import dev.app.leaf.domain.editing.hunkEditOf
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ViewDiffResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Asking what to do with lines typed that aren't saved (fork-only, `docs/fork/inline-editing.md`): Save, Discard or Keep
 * editing. [then] runs after Save or Discard, for a tab that closes, a worktree switch or another repository; it's null
 * when the selection moved on to another file, which the diff pane shows once the edit ends, or when Esc asked.
 */
data class HunkEditLeave(val then: (() -> Unit)?, val isEscape: Boolean = false)

/**
 * A hunk edited in place: the [diff] that the pane showed when editing started, which it keeps showing while the edit
 * lasts, the [hunk] of it, the [edit], what the field has ([value]) and the typed lines that differ from the old side
 * ([changedLines]).
 */
data class HunkEditState(
    val diff: ViewDiffResult.Loaded,
    val hunk: Hunk,
    val edit: HunkEdit,
    val value: TextFieldValue,
    val changedLines: Set<Int>,
    val syntax: EditorSyntax?,
    val isSaving: Boolean = false,
    val leave: HunkEditLeave? = null,
) {
    /** Whether the field has lines that writing would change: leaving without them loses nothing. */
    val hasTypedChanges: Boolean get() = !edit.isUnchanged(value.text)
}

/** Whether [hunk] of the diff [diffType] can be edited in place (`docs/fork/inline-editing.md`, section 2). */
fun canEditHunk(diffType: DiffType, hunk: Hunk): Boolean =
    diffType is DiffType.UncommittedDiff &&
            diffType.entryType == EntryType.UNSTAGED &&
            diffType.statusType in listOf(StatusType.MODIFIED, StatusType.ADDED) &&
            !hunk.ignoresWhitespace &&
            hunkEditOf(diffType.filePath, hunk) != null

/** Whether [this] diff is of the file and the list that [other] is of, whatever its lines are now. */
private fun DiffType.isSameFileAs(other: DiffType): Boolean =
    this is DiffType.UncommittedDiff && other is DiffType.UncommittedDiff &&
            entryType == other.entryType && filePath == other.filePath

/**
 * Editing a hunk of an Unstaged diff in place (fork-only, `docs/fork/inline-editing.md`): one hunk at a time, which
 * Done writes into the working file ([write]), and Cancel leaves as it was. While it lasts, the diff pane shows the diff
 * that editing started from, so that a refresh can't move the lines from under the caret.
 */
class HunkEditController(
    private val scope: CoroutineScope,
    diffResult: Flow<ViewDiffResult?>,
    private val write: (HunkEdit, String) -> Deferred<Either<EditedFile, AppError>>,
    private val syntaxOf: (Hunk, HunkEdit) -> EditorSyntax?,
    /** Told when editing starts and ends, which adds the edit to the views that Esc closes and takes it out. */
    private val onEditing: (Boolean) -> Unit,
) {
    val state: StateFlow<HunkEditState?>
        field = MutableStateFlow<HunkEditState?>(null)

    init {
        scope.launch {
            diffResult.collect { shown -> selectionChanged(shown) }
        }
    }

    /** Starts editing [hunk] of [diff], with the caret at the start of line [caretLine] (from 1, the new side's). */
    fun start(diff: ViewDiffResult.Loaded, hunk: Hunk, caretLine: Int? = null) {
        if (state.value != null || !canEditHunk(diff.diffType, hunk)) return

        val edit = hunkEditOf(diff.diffType.filePath, hunk) ?: return
        val caret = caretLine
            ?.let { line -> (line - 1 - edit.start).coerceIn(0, maxOf(0, edit.lines.size - 1)) }
            ?.let { index -> edit.text.split('\n').take(index).sumOf { it.length + 1 } }
            ?: edit.text.length

        state.value = HunkEditState(
            diff = diff,
            hunk = hunk,
            edit = edit,
            value = TextFieldValue(edit.text, TextRange(caret)),
            changedLines = changedTypedLines(edit, edit.text),
            syntax = syntaxOf(hunk, edit),
        )
        onEditing(true)
    }

    /** What the field has after a change; characters that the file's charset can't hold aren't taken. */
    fun type(value: TextFieldValue) {
        state.update { current ->
            when {
                current == null || current.isSaving -> current
                !current.edit.canWrite(value.text) -> current
                value.text == current.value.text -> current.copy(value = value)
                else -> current.copy(value = value, changedLines = changedTypedLines(current.edit, value.text))
            }
        }
    }

    /** Done: writes the lines when they changed, then ends, and runs [then]. A failure keeps the field open. */
    fun done(then: (() -> Unit)? = null) {
        val current = state.value ?: return
        if (current.isSaving) return

        if (!current.hasTypedChanges) {
            end()
            then?.invoke()
            return
        }

        state.value = current.copy(isSaving = true, leave = null)

        scope.launch {
            val result = write(current.edit, current.value.text).await()

            if (result is Either.Ok) {
                end()
                then?.invoke()
            } else {
                // The error dialog says why; what was typed stays
                state.update { it?.copy(isSaving = false) }
                onEditing(true)
            }
        }
    }

    /** Cancel: the file stays as it was, and what was typed goes. */
    fun cancel() = end()

    /**
     * Esc: ends an edit with nothing typed, asks first about lines typed, and closes the question when it's asked
     * already, which keeps editing. The edit stays among the views that Esc closes while it lasts.
     */
    fun escape() {
        val current = state.value ?: return

        when {
            current.leave != null -> keepEditing()
            current.hasTypedChanges -> {
                state.value = current.copy(leave = HunkEditLeave(then = null, isEscape = true))
                onEditing(true)
            }

            else -> end()
        }
    }

    /** Runs [then], which leaves the file (a tab that closes, a worktree switch), asking first about lines typed. */
    fun leaveThen(then: () -> Unit) {
        val current = state.value

        when {
            current == null -> then()
            !current.hasTypedChanges -> {
                end()
                then()
            }

            else -> state.value = current.copy(leave = HunkEditLeave(then))
        }
    }

    /** The question's Save. */
    fun saveAndLeave() = done(state.value?.leave?.then)

    /** The question's Discard. */
    fun discardAndLeave() {
        val then = state.value?.leave?.then
        end()
        then?.invoke()
    }

    /** The question's Keep editing: what was asked for doesn't happen. */
    fun keepEditing() {
        state.update { it?.copy(leave = null) }
        if (state.value != null) onEditing(true)
    }

    /** Whether the field has lines typed that aren't saved, which closing the tab or quitting would lose. */
    val hasUnsavedChanges: Boolean get() = state.value?.hasTypedChanges == true

    /** The file being edited, when it has lines typed that aren't saved. */
    val unsavedFile: String? get() = state.value?.takeIf { it.hasTypedChanges }?.edit?.path

    /** Saves the lines typed before Leaf quits, then calls [onSaved], or [onFailed] when writing them failed. */
    fun saveBeforeQuit(onSaved: () -> Unit, onFailed: () -> Unit) {
        val current = state.value

        if (current == null || !current.hasTypedChanges) {
            onSaved()
            return
        }

        state.value = current.copy(isSaving = true, leave = null)

        scope.launch {
            if (write(current.edit, current.value.text).await() is Either.Ok) {
                end()
                onSaved()
            } else {
                state.update { it?.copy(isSaving = false) }
                onFailed()
            }
        }
    }

    /**
     * Another diff shown in the pane, or none: an edit with nothing typed ends, and one with lines typed asks, while the
     * pane keeps showing it. Back on the edited file, the question goes.
     */
    private fun selectionChanged(shown: ViewDiffResult?) {
        val current = state.value ?: return
        val shownType = shown?.diffType

        // Loading a diff says nothing yet about where the selection went
        if (shown is ViewDiffResult.Loading) return

        val isSameFile = shownType != null && shownType.isSameFileAs(current.diff.diffType)

        when {
            isSameFile -> if (current.leave?.then == null && current.leave?.isEscape == false) keepEditing()
            !current.hasTypedChanges -> end()
            current.leave == null -> state.value = current.copy(leave = HunkEditLeave(then = null))
        }
    }

    private fun end() {
        if (state.value == null) return

        state.value = null
        onEditing(false)
    }
}
