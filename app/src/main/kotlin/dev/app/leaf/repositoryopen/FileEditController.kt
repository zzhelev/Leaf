// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.conflicts.EditorSyntax
import dev.app.leaf.domain.editing.ChangeMarker
import dev.app.leaf.domain.editing.FileEdit
import dev.app.leaf.domain.editing.FileForEdit
import dev.app.leaf.domain.editing.HunkEdit
import dev.app.leaf.domain.editing.changeMarkers
import dev.app.leaf.domain.editing.editedFile
import dev.app.leaf.domain.editing.fileEditOf
import dev.app.leaf.domain.editing.puttingBack
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ViewDiffResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long typing has to stop before the text's colours are worked out again, as the resolver's field waits. */
private const val COLOURING_DELAY = 80L

/**
 * The working file being edited whole (fork-only, `docs/fork/inline-editing.md`, B2): the Unstaged file it was opened
 * for ([diffType]), what editing started from or was last saved ([edit]), the file's version in the index
 * ([indexText]), what the field has ([value]), and the changes against the index ([markers]). [openMarker] shows its old
 * lines with Put it back.
 */
data class FileEditState(
    val diffType: DiffType.UncommittedDiff,
    val edit: FileEdit,
    val indexText: String?,
    val value: TextFieldValue,
    val markers: List<ChangeMarker>,
    val syntax: EditorSyntax?,
    val isSaving: Boolean = false,
    val leave: HunkEditLeave? = null,
    val openMarker: ChangeMarker? = null,
) {
    /** Whether the field has lines that Save would write: leaving without them loses nothing. */
    val hasUnsavedChanges: Boolean get() = !edit.isUnchanged(value.text)
}

/** Whether the file of [diffType] can be edited whole: an Unstaged text file, modified or new (B2, section 9). */
fun canEditWholeFile(diffType: DiffType?): Boolean =
    diffType is DiffType.UncommittedDiff &&
            diffType.entryType == EntryType.UNSTAGED &&
            diffType.statusType in listOf(StatusType.MODIFIED, StatusType.ADDED)

/**
 * Editing a working file whole (fork-only, `docs/fork/inline-editing.md`, B2), in the diff pane's place: [read] reads
 * it with its index version, [write] saves it, and [highlight] colours the text a moment after typing stops. Save stays
 * in the editor; Back to diff, and anything else that leaves it, asks first about lines that aren't saved, as a hunk
 * edited in place does.
 */
class FileEditController(
    private val scope: CoroutineScope,
    diffResult: Flow<ViewDiffResult?>,
    private val read: (path: String) -> Deferred<Either<FileForEdit, AppError>>,
    private val write: (FileEdit, String) -> Deferred<Either<ByteArray, AppError>>,
    private val highlight: suspend (path: String, text: String) -> HighlightedText?,
    /** Told when the editor opens and closes, which adds it to the views that Esc closes and takes it out. */
    private val onEditing: (Boolean) -> Unit,
) {
    val state: StateFlow<FileEditState?>
        field = MutableStateFlow<FileEditState?>(null)

    private var markersJob: Job? = null
    private var colouringJob: Job? = null

    init {
        scope.launch {
            diffResult.collect { shown -> selectionChanged(shown) }
        }
    }

    /**
     * Opens the file of [diffType], with the caret at the start of [caretLine] (from 1). With [carried], a hunk edited in
     * place whose lines were typed, the editor starts with them in the file, not saved yet; [onOpened] runs once it shows.
     */
    fun open(
        diffType: DiffType.UncommittedDiff,
        caretLine: Int? = null,
        carried: Pair<HunkEdit, String>? = null,
        onOpened: () -> Unit = {},
    ) {
        if (state.value != null || !canEditWholeFile(diffType)) return

        scope.launch {
            val file = (read(diffType.filePath).await() as? Either.Ok)?.value ?: return@launch
            val edit = fileEditOf(diffType.filePath, file.bytes) ?: return@launch
            val text = carried
                ?.let { (hunk, typed) -> editedFile(file.bytes, hunk, typed) }
                ?.let { edited -> fileEditOf(diffType.filePath, edited.bytes)?.text }
                ?: edit.text
            val caret = caretLine
                ?.let { line -> text.split('\n').take((line - 1).coerceAtLeast(0)).sumOf { it.length + 1 } }
                ?.coerceAtMost(text.length)
                ?: 0

            state.value = FileEditState(
                diffType = diffType,
                edit = edit,
                indexText = file.indexText,
                value = TextFieldValue(text, TextRange(caret)),
                markers = withContext(Dispatchers.Default) { changeMarkers(file.indexText, text) },
                syntax = null,
            )
            onEditing(true)
            onOpened()
            colourLater(text, now = true)
        }
    }

    /** What the field has after a change; characters that the file's charset can't hold aren't taken. */
    fun type(value: TextFieldValue) {
        val current = state.value ?: return
        if (current.isSaving || !current.edit.canWrite(value.text)) return

        val textChanged = value.text != current.value.text
        state.value = current.copy(value = value, openMarker = if (textChanged) null else current.openMarker)

        if (textChanged) {
            updateMarkers(value.text)
            colourLater(value.text)
        }
    }

    /** Save: writes the file and stays, then runs [then]. A failure keeps what was typed. */
    fun save(then: (() -> Unit)? = null) {
        val current = state.value ?: return
        if (current.isSaving) return

        if (!current.hasUnsavedChanges) {
            state.update { it?.copy(leave = null) }
            then?.invoke()
            return
        }

        state.value = current.copy(isSaving = true, leave = null)

        scope.launch {
            val result = write(current.edit, current.value.text).await()

            state.update { now ->
                when {
                    now == null -> null
                    result is Either.Ok -> now.copy(
                        edit = fileEditOf(now.edit.path, result.value) ?: now.edit,
                        isSaving = false,
                    )

                    else -> now.copy(isSaving = false)
                }
            }

            if (result is Either.Ok) then?.invoke() else onEditing(true)
        }
    }

    /** ⌘↵: Save, then back to the diff. */
    fun saveAndClose() = save { close() }

    /** Back to diff, and Esc: closes, asking first about lines that aren't saved, and closes the question when asked. */
    fun back() {
        val current = state.value ?: return

        when {
            current.openMarker != null -> state.value = current.copy(openMarker = null).also { onEditing(true) }
            current.leave != null -> keepEditing()
            current.hasUnsavedChanges -> {
                state.value = current.copy(leave = HunkEditLeave(then = null, isEscape = true))
                onEditing(true)
            }

            else -> close()
        }
    }

    /** Runs [then], which leaves the file (a tab that closes, a worktree switch), asking first about lines not saved. */
    fun leaveThen(then: () -> Unit) {
        val current = state.value

        when {
            current == null -> then()
            !current.hasUnsavedChanges -> {
                close()
                then()
            }

            else -> state.value = current.copy(leave = HunkEditLeave(then))
        }
    }

    /** The question's Save: saves, closes, and runs what was asked for. */
    fun saveAndLeave() {
        val then = state.value?.leave?.then
        save {
            close()
            then?.invoke()
        }
    }

    /** The question's Discard. */
    fun discardAndLeave() {
        val then = state.value?.leave?.then
        close()
        then?.invoke()
    }

    /** The question's Keep editing. */
    fun keepEditing() {
        state.update { it?.copy(leave = null) }
        if (state.value != null) onEditing(true)
    }

    /** Shows [marker]'s old lines with Put it back, or hides them. */
    fun showMarker(marker: ChangeMarker?) {
        state.update { it?.copy(openMarker = marker) }
        if (marker != null) onEditing(true)
    }

    /** Put it back: the index's lines in place of [marker]'s, with the caret where they start. */
    fun putBack(marker: ChangeMarker) {
        val current = state.value ?: return
        val text = puttingBack(current.value.text, marker)
        val caret = text.split('\n').take(marker.start).sumOf { it.length + 1 }.coerceAtMost(text.length)

        type(TextFieldValue(text, TextRange(caret)))
        state.update { it?.copy(openMarker = null) }
    }

    val hasUnsavedChanges: Boolean get() = state.value?.hasUnsavedChanges == true

    /** The file being edited, when it has lines that aren't saved. */
    val unsavedFile: String? get() = state.value?.takeIf { it.hasUnsavedChanges }?.edit?.path

    /** Saves before Leaf quits, then calls [onSaved], or [onFailed] when the file couldn't be written. */
    fun saveBeforeQuit(onSaved: () -> Unit, onFailed: () -> Unit) {
        val current = state.value

        if (current == null || !current.hasUnsavedChanges) {
            onSaved()
            return
        }

        state.value = current.copy(isSaving = true, leave = null)

        scope.launch {
            if (write(current.edit, current.value.text).await() is Either.Ok) {
                close()
                onSaved()
            } else {
                state.update { it?.copy(isSaving = false) }
                onFailed()
            }
        }
    }

    /** Another file's diff, or none: closes without lines unsaved, else asks, and the question goes back on the file. */
    private fun selectionChanged(shown: ViewDiffResult?) {
        val current = state.value ?: return
        if (shown is ViewDiffResult.Loading) return

        val shownType = shown?.diffType
        val isSameFile = shownType is DiffType.UncommittedDiff && shownType.entryType == EntryType.UNSTAGED &&
                shownType.filePath == current.diffType.filePath

        when {
            isSameFile -> if (current.leave?.then == null && current.leave?.isEscape == false) keepEditing()
            !current.hasUnsavedChanges -> close()
            current.leave == null -> state.value = current.copy(leave = HunkEditLeave(then = null))
        }
    }

    private fun updateMarkers(text: String) {
        markersJob?.cancel()
        markersJob = scope.launch(Dispatchers.Default) {
            val index = state.value?.indexText
            val markers = changeMarkers(index, text)

            state.update { now -> if (now?.value?.text == text) now.copy(markers = markers) else now }
        }
    }

    private fun colourLater(text: String, now: Boolean = false) {
        val path = state.value?.edit?.path ?: return

        colouringJob?.cancel()
        colouringJob = scope.launch {
            if (!now) delay(COLOURING_DELAY)
            val highlighted = highlight(path, text) ?: return@launch

            state.update { current ->
                if (current?.value?.text == text) current.copy(syntax = EditorSyntax(text, highlighted)) else current
            }
        }
    }

    private fun close() {
        if (state.value == null) return

        markersJob?.cancel()
        colouringJob?.cancel()
        state.value = null
        onEditing(false)
    }
}
