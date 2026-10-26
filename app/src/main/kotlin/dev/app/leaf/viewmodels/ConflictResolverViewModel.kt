// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.conflicts.ColumnShares
import dev.app.leaf.domain.conflicts.DEFAULT_FILE_LIST_WIDTH
import dev.app.leaf.domain.conflicts.EditStart
import dev.app.leaf.domain.conflicts.EditorSyntax
import dev.app.leaf.domain.conflicts.applying
import dev.app.leaf.domain.conflicts.conflictChunks
import dev.app.leaf.domain.conflicts.dropping
import dev.app.leaf.domain.conflicts.editStart
import dev.app.leaf.domain.conflicts.editStartSyntax
import dev.app.leaf.domain.conflicts.firstChangedChunk
import dev.app.leaf.domain.conflicts.inParts
import dev.app.leaf.domain.conflicts.resolving
import dev.app.leaf.domain.conflicts.spansFor
import dev.app.leaf.domain.conflicts.taking
import dev.app.leaf.domain.conflicts.textBefore
import dev.app.leaf.domain.conflicts.typedLines
import dev.app.leaf.domain.conflicts.typing
import dev.app.leaf.domain.conflicts.withoutTyping
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.highlighting.decodedText
import dev.app.leaf.domain.highlighting.highlightedTextOf
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsObjectDownloadError
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.RenamedFile
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TypedLines
import dev.app.leaf.domain.models.VersionPreview
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.DownloadLfsVersionUseCase
import dev.app.leaf.domain.usecases.GetConflictedFileUseCase
import dev.app.leaf.domain.usecases.GetRenamedFilesUseCase
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import dev.app.leaf.domain.usecases.MarkConflictResolvedUseCase
import dev.app.leaf.domain.usecases.OpenConflictVersionUseCase
import dev.app.leaf.domain.usecases.OpenMergeToolUseCase
import dev.app.leaf.domain.usecases.ResolveConflictUseCase
import dev.app.leaf.domain.usecases.ResolveRenamedFileUseCase
import dev.app.leaf.domain.usecases.SaveConflictResolutionUseCase
import dev.app.leaf.domain.usecases.StatusStageUseCase
import dev.app.leaf.ui.conflicts.MAX_COLOURED_EDIT_CHARACTERS
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.RawText
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The built-in conflict resolver's whole-tab view (`docs/fork/conflict-resolver.md`, steps 2 and 3): the conflicted
 * files, and the one shown, with the picks made in it and the layout. Opened and closed by `RepositoryOpenViewModel`,
 * as file history is.
 *
 * The list follows the status: a file that stops being conflicted, because it was marked resolved here or resolved
 * elsewhere, gets a ✓ (if it was resolved here), and the view stays on it, read-only, until the user opens the next one
 * ([nextFile]). When none is left, the view says so, or closes when nothing was resolved here.
 *
 * One part of the result at a time can be edited (step 4, [startEdit]), and Undo and Redo go back and forth through the
 * picks and the finished edits of the file shown.
 *
 * A conflict that three panes can't show gets a card per side (step 5): [pick] picks one, and [markResolved] stages it.
 * A file that each side renamed differently is one entry of the list, and opens as one, whichever of its paths.
 */
class ConflictResolverViewModel @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getConflictedFileUseCase: GetConflictedFileUseCase,
    private val saveConflictResolutionUseCase: SaveConflictResolutionUseCase,
    private val markConflictResolvedUseCase: MarkConflictResolvedUseCase,
    private val statusStageUseCase: StatusStageUseCase,
    private val resolveConflictUseCase: ResolveConflictUseCase,
    private val resolveRenamedFileUseCase: ResolveRenamedFileUseCase,
    private val getRenamedFilesUseCase: GetRenamedFilesUseCase,
    private val openMergeToolUseCase: OpenMergeToolUseCase,
    private val openConflictVersionUseCase: OpenConflictVersionUseCase,
    private val downloadLfsVersionUseCase: DownloadLfsVersionUseCase,
    private val appSettingsService: AppSettingsService,
    private val session: ConflictResolverSession,
    // Fork-only: the versions' syntax colours
    private val highlightTextUseCase: HighlightTextUseCase,
) : TabViewModel() {
    val state: StateFlow<ConflictResolverState>
        field = MutableStateFlow(
            ConflictResolverState(
                stackedSplit = session.stackedSplit,
                columnShares = session.columnShares,
                fileListWidth = session.fileListWidth,
                focused = session.focused,
            )
        )

    /** A conflict (its chunk's index) to scroll to, after a move to the next or the previous one. */
    val scrollToChunk: SharedFlow<Int>
        field = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** A part (its chunk's index) that Undo or Redo changed, to scroll to when it's out of sight. */
    val revealChunk: SharedFlow<Int>
        field = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    private var onClose: () -> Unit = {}
    private var loadJob: Job? = null
    private var conflicted: List<StatusEntry> = emptyList()
    private val resolvedHere = mutableListOf<StatusEntry>()
    private val history = ResolverHistory()

    /** The colouring of the file shown, which loading another one stops (fork-only). */
    private var colouring = SupervisorJob(viewModelScope.coroutineContext.job)

    /** The texts being coloured: the versions and the lines typed, by instance. */
    private val beingColoured: MutableSet<RawText> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    /** The colouring of the part being edited, which each change starts again. */
    private var editColouring: Job? = null

    /** The file that the external merge tool has: it's loaded again once the status comes back with it conflicted. */
    private var inExternalTool: String? = null

    /** The downloads of Git LFS objects that the cards started, by object id. */
    private val lfsDownloadJobs = ConcurrentHashMap<String, Job>()

    /** The files that each side renamed differently, as the index had them at the last status. */
    private var renamed: List<RenamedFile> = emptyList()

    /** The new names of the renamed files seen, by old name, for the list's entries once they're resolved. */
    private val newNames = mutableMapOf<String, List<String>>()

    /**
     * The name picked for a renamed file whose content has conflicts: it's a conflict of its content once the rename is
     * resolved, and opens next.
     */
    private var follow: String? = null

    /** Opens [path], or the first conflicted file. [onClose] closes the view. */
    fun open(path: String?, onClose: () -> Unit) {
        this.onClose = onClose

        // Before the status comes: with no file shown, it would open the first conflicted one
        if (path != null) {
            load(path)
        }

        viewModelScope.launch {
            appSettingsService.conflictResolverLayout.collect { layout -> state.update { it.copy(layout = layout) } }
        }

        viewModelScope.launch {
            appSettingsService.conflictResolverWrapLines.collect { wrap -> state.update { it.copy(wrapLines = wrap) } }
        }

        // Fork-only: the versions of the file shown, and the lines typed in it, get their colours
        viewModelScope.launch {
            state
                .map { state -> (state.file as? ResolverFile.Loaded)?.let { file -> file.path to file.merge } }
                .distinctUntilChanged { old, new -> old?.first == new?.first && old?.second === new?.second }
                .collect { shown -> shown?.second?.let { merge -> colourVersions(shown.first, merge) } }
        }

        viewModelScope.launch {
            repositoryDataRepository.status.collect { status ->
                if (status is DataState.Loaded) {
                    onStatus(status.data.unstaged.filter { it.statusType == StatusType.CONFLICTING })
                }
            }
        }
    }

    // Files and leaving

    fun selectFile(path: String) {
        if (path == state.value.file.path || resolvedHere.any { it.filePath == path }) {
            return
        }

        leave(ResolverLeave.OpenFile(path))
    }

    /** Closes the view, asking first when the file has changes that aren't saved. */
    fun close() = leave(ResolverLeave.Close)

    /**
     * Esc: finishes editing first, then leaves Focus, then closes the view (asking first when the file has changes that
     * aren't saved).
     */
    fun escape() {
        if (state.value.edit != null) {
            finishEdit()
        } else if (state.value.focused) {
            toggleFocus()
        } else {
            close()
        }
    }

    /** Closes the view without asking, as when the tab switches to another worktree. */
    fun closeNow() {
        loadJob?.cancel()
        onClose()
    }

    /**
     * Runs [then], which leaves the file, such as closing the tab or switching it to another worktree, once the file
     * has no changes that aren't saved, asking first.
     */
    fun leaveThen(then: () -> Unit) = leave(ResolverLeave.Then(then))

    /** Whether leaving the file would lose changes: picks, or lines typed, that aren't saved. */
    val hasUnsavedChanges: Boolean
        get() {
            val edit = state.value.edit

            return !paused && (state.value.file.hasUnsavedChanges || edit != null && edit.value.text != edit.start.text)
        }

    /** The file shown, when it has changes that aren't saved. */
    val unsavedFile: String?
        get() = state.value.file.path?.takeIf { hasUnsavedChanges }

    private fun leave(leave: ResolverLeave) {
        finishEdit()

        // A resolved file was written and staged: its picks have nowhere left to go
        if (hasUnsavedChanges) {
            state.update { it.copy(prompt = ResolverPrompt.UnsavedChanges(leave)) }
        } else {
            go(leave)
        }
    }

    private fun go(leave: ResolverLeave) = when (leave) {
        ResolverLeave.Close -> closeNow()
        is ResolverLeave.OpenFile -> load(leave.path)
        is ResolverLeave.Then -> leave.then()
    }

    /** The answer to [ResolverPrompt.UnsavedChanges]: save first, or not, or stay ([save] null). */
    fun answerUnsavedChanges(save: Boolean?) {
        val prompt = state.value.prompt as? ResolverPrompt.UnsavedChanges ?: return

        state.update { it.copy(prompt = null) }

        when (save) {
            null -> Unit
            false -> go(prompt.leave)
            true -> save(then = { go(prompt.leave) })
        }
    }

    fun dismissPrompt() {
        state.update { it.copy(prompt = null) }
    }

    /** After a file was resolved: opens the next conflicted file (the strip's button, or Cmd+Enter again). */
    fun nextFile() {
        val handOff = state.value.handOff ?: return

        load(handOff.next)
    }

    /** The shown file has no conflicts any more, and the view waits for [nextFile]: it's read-only. */
    private val paused: Boolean get() = state.value.handOff != null

    // Editing

    /**
     * Turns [chunk]'s lines in the result into a text field, with the caret at the end of its line [line], or of its
     * last line. It starts from the lines that the result shows there, or the base's for an open conflict. A part
     * that was being edited is finished first.
     */
    fun startEdit(chunk: Int, line: Int? = null) {
        if (paused || state.value.edit?.chunk == chunk) {
            return
        }

        finishEdit()

        val merge = loadedMerge() ?: return

        if (chunk !in merge.chunks.indices) {
            return
        }

        val start = merge.editStart(chunk)

        state.update {
            val value = TextFieldValue(start.text, TextRange(caretAt(start.text, line)))
            it.copy(edit = ResolverEdit(chunk, value, start, line, merge.editStartSyntax(chunk, it.syntax)))
        }
        selectConflict(chunk)
    }

    /** Enter: edits the current conflict. */
    fun editCurrentConflict() {
        val merge = loadedMerge() ?: return
        val chunk = merge.conflictChunks().getOrNull(state.value.currentConflict) ?: return

        startEdit(chunk)
    }

    /** The text field changed. Characters that the file's encoding can't hold aren't taken. */
    fun typeInEdit(value: TextFieldValue) {
        val edit = state.value.edit ?: return

        if (!edit.start.format.canWrite(value.text)) {
            return
        }

        state.update { it.copy(edit = it.edit?.copy(value = value)) }

        if (value.text != edit.value.text) {
            colourEdit()
        }
    }

    /**
     * Puts the lines typed in place of the part's lines, as one step for Undo. A text that didn't change leaves the
     * part as it was: an open conflict stays open.
     */
    fun finishEdit() {
        val edit = state.value.edit ?: return
        editColouring?.cancel()

        if (edit.value.text == edit.start.text) {
            state.update { it.copy(edit = null) }
            return
        }

        val typed = edit.start.format.typed(edit.value.text)
        val path = state.value.file.path

        // Fork-only: the lines typed keep the field's colours in the rows, coloured again unless they're the text's own
        state.update { state ->
            val syntax = edit.syntax
            val seed = when {
                syntax == null -> null
                syntax.text == edit.value.text -> syntax.highlighted
                else -> highlightedTextOf(syntax.spansFor(edit.value.text.split('\n')))
            }
            if (path == null || seed == null) {
                state.copy(edit = null)
            } else {
                state.copy(edit = null, syntax = state.syntax.with(typed.text, path, seed))
            }
        }
        updateMerge { it.typing(edit.chunk, typed) }

        val merge = loadedMerge()

        if (path != null && merge != null && edit.syntax?.text != edit.value.text) {
            colourTyped(path, merge, edit.chunk, typed)
        }
    }

    /**
     * Colours the part being edited again, a moment after its text changes, after the lines that come before it in ours
     * (fork-only).
     */
    private fun colourEdit() {
        editColouring?.cancel()
        val edit = state.value.edit ?: return
        val file = state.value.file as? ResolverFile.Loaded ?: return
        val merge = file.merge ?: return
        val text = edit.value.text

        if (text.length > MAX_COLOURED_EDIT_CHARACTERS) {
            return
        }

        editColouring = viewModelScope.launch(colouring) {
            delay(EDIT_COLOURING_DELAY)
            val highlighted = highlightTextUseCase.inContext(file.path, merge.textBefore(edit.chunk), text) ?: return@launch

            state.update { state ->
                val shown = state.edit
                if (shown != null && shown.chunk == edit.chunk) {
                    state.copy(edit = shown.copy(syntax = EditorSyntax(text, highlighted)))
                } else {
                    state
                }
            }
        }
    }

    /** Colours the versions of [merge], the file at [path], and the lines typed in it, that have no colours yet. */
    private fun colourVersions(path: String, merge: TextMerge) {
        val known = state.value.syntax

        for (text in listOf(merge.base, merge.ours, merge.theirs)) {
            if (known.versionOf(text) == null && beingColoured.add(text)) {
                colour(path, text) { highlightTextUseCase(path, text) }
            }
        }

        for ((chunk, typed) in merge.typedLines()) {
            if (known.versionOf(typed.text) == null) {
                colourTyped(path, merge, chunk, typed)
            }
        }
    }

    /** Colours [typed], in [chunk]'s place, after the lines that come before it in ours. */
    private fun colourTyped(path: String, merge: TextMerge, chunk: Int, typed: TypedLines) {
        if (beingColoured.add(typed.text)) {
            colour(path, typed.text) {
                highlightTextUseCase.inContext(path, merge.textBefore(chunk), typed.text.decodedText())
            }
        }
    }

    /** Keeps [text]'s colours, which [highlight] works out, while the file at [path] is shown: none when nothing colours it. */
    private fun colour(path: String, text: RawText, highlight: suspend () -> HighlightedText?) {
        viewModelScope.launch(colouring) {
            try {
                val highlighted = highlight()
                state.update { state ->
                    if (state.file.path == path) state.copy(syntax = state.syntax.with(text, path, highlighted)) else state
                }
            } finally {
                beingColoured.remove(text)
            }
        }
    }

    /** Puts [chunk]'s lines, unchanged lines or a change, back as the merge made them, in place of lines typed there. */
    fun putBack(chunk: Int) = updateMerge { it.withoutTyping(chunk) }

    /** Takes the last pick or edit back. While a part is edited, its text field undoes the typing instead. */
    fun undo() = step { merge -> history.undo(merge) }

    fun redo() = step { merge -> history.redo(merge) }

    private fun step(next: (TextMerge) -> TextMerge?) {
        if (paused || state.value.edit != null) {
            return
        }

        val file = state.value.file as? ResolverFile.Loaded ?: return
        val merge = file.merge ?: return
        val stepped = next(merge) ?: return

        state.update { state ->
            if (state.file === file) state.copy(file = file.copy(merge = stepped)) else state
        }
        publishHistory()

        stepped.firstChangedChunk(merge)?.let { chunk ->
            selectConflict(chunk)
            revealChunk.tryEmit(chunk)
        }
    }

    private fun publishHistory() {
        state.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
    }

    private fun loadedMerge(): TextMerge? = (state.value.file as? ResolverFile.Loaded)?.merge

    // Picks

    /** A side's arrow on [chunk]'s conflict: takes that side, or adds it after the other side's lines. */
    fun take(chunk: Int, side: ConflictSide) = updateMerge { merge ->
        val conflict = merge.chunks[chunk] as? TextMerge.Chunk.Conflict ?: return@updateMerge merge

        merge.resolving(chunk, conflict.resolution.taking(side))
    }

    /** A side's Taken chip in the stacked layout: takes that side's lines out of [chunk]'s result. */
    fun drop(chunk: Int, side: ConflictSide) = updateMerge { merge ->
        val conflict = merge.chunks[chunk] as? TextMerge.Chunk.Conflict ?: return@updateMerge merge

        merge.resolving(chunk, conflict.resolution.dropping(side))
    }

    fun resolve(chunk: Int, resolution: ConflictResolution) = updateMerge { it.resolving(chunk, resolution) }

    fun setApplied(chunk: Int, applied: Boolean) = updateMerge { it.applying(chunk, applied) }

    /**
     * Changes the merge of the file shown, as one step for Undo. A part being edited is finished first, as one step
     * of its own. Nothing changes while the file is resolved and the view waits to open the next one.
     */
    private fun updateMerge(change: (TextMerge) -> TextMerge) {
        if (state.value.edit != null) {
            finishEdit()
        }

        val file = state.value.file as? ResolverFile.Loaded ?: return
        val merge = file.merge ?: return
        val changed = change(merge)

        if (paused || changed == merge) {
            return
        }

        var applied = false

        // The status can load another file meanwhile, on another thread
        state.update { state ->
            applied = state.file === file && state.handOff == null
            if (applied) state.copy(file = file.copy(merge = changed)) else state
        }

        if (applied) {
            history.record(merge)
            publishHistory()
        }
    }

    fun toggleBase() {
        state.update { it.copy(showBase = !it.showBase) }
    }

    /** Switches the layout, for every resolver in the app (Settings → Conflicts has the same choice). */
    fun setLayout(layout: ConflictResolverLayout) {
        finishEdit()
        state.update { it.copy(layout = layout) }

        viewModelScope.launch {
            appSettingsService.setConfiguration(AppConfig.ConflictResolverLayout(layout))
        }
    }

    /** The stacked layout's handle was dragged: the top pair's share of the height, kept until Leaf quits. */
    fun setStackedSplit(split: Float) {
        session.stackedSplit = split
        state.update { it.copy(stackedSplit = split) }
    }

    /** A gutter of the Columns layout was dragged, kept until Leaf quits. */
    fun setColumnShares(shares: ColumnShares) {
        session.columnShares = shares
        state.update { it.copy(columnShares = shares) }
    }

    /** The file list's edge was dragged, kept until Leaf quits. */
    fun setFileListWidth(width: Float) {
        session.fileListWidth = width
        state.update { it.copy(fileListWidth = width) }
    }

    /** Focus: the rest of Leaf hides, and the file list too, until it's turned off. Kept until Leaf quits. */
    fun toggleFocus() {
        val focused = !state.value.focused

        session.focused = focused
        state.update { it.copy(focused = focused) }
    }

    /** Long lines wrap, or stay on one row and scroll sideways together, for the whole app (Settings → Conflicts). */
    fun toggleWrapLines() {
        val wrap = !state.value.wrapLines

        state.update { it.copy(wrapLines = wrap) }

        viewModelScope.launch {
            appSettingsService.setConfiguration(AppConfig.ConflictResolverWrapLines(wrap))
        }
    }

    // Conflicts

    fun nextConflict() = moveConflict(+1)

    fun previousConflict() = moveConflict(-1)

    private fun moveConflict(step: Int) {
        finishEdit()

        val file = state.value.file as? ResolverFile.Loaded ?: return
        val conflicts = file.merge?.conflictChunks() ?: return
        val index = (state.value.currentConflict + step).coerceIn(0, conflicts.lastIndex)

        if (conflicts.isEmpty() || index == state.value.currentConflict) {
            return
        }

        state.update { it.copy(currentConflict = index) }
        scrollToChunk.tryEmit(conflicts[index])
    }

    /** Makes the conflict of [chunk] the current one, as when it's clicked. */
    fun selectConflict(chunk: Int) {
        val index = (state.value.file as? ResolverFile.Loaded)?.merge?.conflictChunks()?.indexOf(chunk) ?: return

        if (index >= 0) {
            state.update { it.copy(currentConflict = index) }
        }
    }

    // Saving

    /**
     * Writes the result, markers for open conflicts included. [overwriteChanges]: over changes made since. [then] runs
     * once it's written, and [orElse] when it couldn't be, which the strip says. Returns the save's job, or null when
     * there's nothing to save.
     */
    fun save(overwriteChanges: Boolean = false, then: () -> Unit = {}, orElse: () -> Unit = {}): Job? {
        finishEdit()

        val file = state.value.file as? ResolverFile.Loaded ?: return null
        val merge = file.merge ?: return null

        if (paused) {
            return null
        }

        return viewModelScope.launch {
            when (val result = saveConflictResolutionUseCase(file.file, merge, overwriteChanges)) {
                is Either.Ok -> {
                    state.update { state ->
                        val current = state.file as? ResolverFile.Loaded

                        if (current?.file?.path != file.file.path) {
                            state
                        } else {
                            state.copy(file = current.copy(file = current.file.withContentId(result.value), saved = merge))
                        }
                    }

                    then()
                }

                is Either.Err -> {
                    showError(result.error, onKeep = { save(overwriteChanges = true, then, orElse) })
                    orElse()
                }
            }
        }
    }

    /**
     * Saves the file before Leaf quits (fork-only): [onSaved] once it's written, or at once when nothing needs saving;
     * [onFailed] when it couldn't be written, which the strip then says.
     */
    fun saveBeforeQuit(onSaved: () -> Unit, onFailed: () -> Unit) {
        finishEdit()

        if (!hasUnsavedChanges) {
            onSaved()
            return
        }

        val job = save(then = onSaved, orElse = onFailed)

        // A save that ends without its callbacks, as when its tab closes meanwhile, stops the quit rather than leave it
        // waiting
        if (job == null) onFailed() else job.invokeOnCompletion { cause -> if (cause != null) onFailed() }
    }

    /** Saves and stages the file, once no conflict is open. Once it's resolved, opens the next one. */
    fun markResolved(overwriteChanges: Boolean = false) {
        if (paused) {
            return nextFile()
        }

        finishEdit()

        val file = state.value.file as? ResolverFile.Loaded ?: return
        val merge = file.merge ?: return stagePick(file)

        if (merge.openConflicts > 0) {
            return
        }

        // Before the use case's status refresh, which moves on to the next file
        markResolvedHere(file.file.path)

        viewModelScope.launch {
            val result = markConflictResolvedUseCase(file.file, merge, overwriteChanges)

            if (result is Either.Err) {
                resolvedHere.removeAll { it.filePath == file.file.path }
                showError(result.error, onKeep = { markResolved(overwriteChanges = true) })
            }
        }
    }

    /** A file without conflict markers: stages it as it is. */
    fun markResolvedAsItIs() {
        val path = state.value.file.path?.takeUnless { paused } ?: return

        markResolvedHere(path)
        statusStageUseCase(StatusEntry(path, StatusType.CONFLICTING))
    }

    /** A file without conflict markers: shows the merge, which Save writes over the file. */
    fun resolveAgain() = updateLoaded { if (paused) it else it.copy(resolveAgain = true) }

    /** Loads the file again, dropping the picks. */
    fun reload() {
        val path = state.value.file.path ?: return

        state.update { it.copy(prompt = null) }
        load(path)
    }

    /** A conflict that three panes can't show: picks [side]'s card, which [markResolved] stages. */
    fun pick(side: ConflictSide) = pick(ChoicePick.of(side))

    /** A file that each side renamed differently: picks keeping both new names. */
    fun pickBothNames() = pick(ChoicePick.BOTH)

    private fun pick(pick: ChoicePick) = updateLoaded { file ->
        val allowed = file.file.hasChoiceCards && (pick != ChoicePick.BOTH || file.file.content is ConflictContent.Renamed)

        if (paused || !allowed) file else file.copy(pick = pick)
    }

    /**
     * Stages the side picked, as "Use ours" and "Use theirs" do. Where one side has no file at the path (it deleted it,
     * or git put it at another path), keeping the other stages the working file as it is, as `git mergetool` does, so
     * that edits made to it stay. A renamed file keeps the names picked.
     */
    private fun stagePick(file: ResolverFile.Loaded) {
        val pick = file.pick ?: return
        val content = file.file.content

        markResolvedHere(file.file.path)

        if (content is ConflictContent.Renamed) {
            val keep = pick.sides

            follow = content.file.path(keep.first()).takeIf { content.contentConflicts }
            resolveRenamedFileUseCase(content.file, keep)
        } else {
            resolveConflictUseCase(
                filePaths = listOf(file.file.path),
                side = pick.sides.single(),
                keepWorkingFile = content == ConflictContent.Deleted || content == ConflictContent.OtherPaths,
            )
        }
    }

    /** Opens one version of the file shown, a stage of the index, in the system's app for its type. */
    fun openVersion(stage: ConflictStage) {
        val path = state.value.file.path ?: return

        viewModelScope.launch {
            val result = openConflictVersionUseCase(stage.objectId, path)

            if (result is Either.Err) {
                state.update { it.copy(prompt = ResolverPrompt.Failed(result.error)) }
            }
        }
    }

    /**
     * Downloads [obj], the Git LFS object of a version of the file shown, which its card says isn't downloaded. The
     * card shows the bytes received, then the version as it is once downloaded, or why it couldn't be. The download
     * goes on when another file is opened, and every card of the object follows it.
     */
    fun downloadLfsObject(obj: LfsObject) {
        val path = state.value.file.path ?: return

        if (lfsDownloadJobs[obj.oid]?.isActive == true) {
            return
        }

        setLfsDownload(obj.oid, LfsDownload.Running(received = 0))

        lfsDownloadJobs[obj.oid] = viewModelScope.launch {
            val result = downloadLfsVersionUseCase(path, obj) { received ->
                // Not after Cancel, which took the download out
                state.update { state ->
                    if (state.lfsDownloads[obj.oid] is LfsDownload.Running) {
                        state.copy(lfsDownloads = state.lfsDownloads + (obj.oid to LfsDownload.Running(received)))
                    } else {
                        state
                    }
                }
            }

            lfsDownloadJobs.remove(obj.oid, coroutineContext.job)

            when (result) {
                is Either.Ok -> {
                    setLfsDownload(obj.oid, null)
                    updateLoaded { file ->
                        if (file.path == path) file.copy(file = file.file.withLfsVersion(result.value)) else file
                    }
                }

                is Either.Err -> setLfsDownload(obj.oid, lfsDownloadFailure(result.error))
            }
        }
    }

    /** Stops the download of [obj]. What it received so far is deleted. */
    fun cancelLfsDownload(obj: LfsObject) {
        lfsDownloadJobs.remove(obj.oid)?.cancel()
        setLfsDownload(obj.oid, null)
    }

    private fun setLfsDownload(oid: String, download: LfsDownload?) = state.update { state ->
        val downloads = if (download == null) state.lfsDownloads - oid else state.lfsDownloads + (oid to download)

        state.copy(lfsDownloads = downloads)
    }

    /** Hands the file to the external merge tool. It's loaded again when the tool is done. */
    fun openInExternalTool() {
        val path = state.value.file.path?.takeUnless { paused } ?: return

        inExternalTool = path
        openMergeToolUseCase(path)
    }

    private fun markResolvedHere(path: String) {
        val entry = conflicted.firstOrNull { it.filePath == path } ?: StatusEntry(path, StatusType.CONFLICTING)

        if (resolvedHere.none { it.filePath == path }) {
            resolvedHere += entry
        }
    }

    private fun showError(error: AppError, onKeep: () -> Unit) {
        val prompt = if (error is ConflictError.WorkingFileChanged) {
            ResolverPrompt.WorkingFileChanged(onKeep)
        } else {
            ResolverPrompt.Failed(error)
        }

        state.update { it.copy(prompt = prompt) }
    }

    private fun updateLoaded(change: (ResolverFile.Loaded) -> ResolverFile.Loaded) {
        state.update { state ->
            val file = state.file as? ResolverFile.Loaded ?: return@update state

            state.copy(file = change(file))
        }
    }

    // Loading and the status

    private fun load(path: String) {
        loadJob?.cancel()
        history.clear()
        // Fork-only: another file's versions
        colouring.cancel()
        colouring = SupervisorJob(viewModelScope.coroutineContext.job)
        beingColoured.clear()
        state.update {
            it.copy(
                file = ResolverFile.Loading(path),
                syntax = HighlightedVersions(),
                currentConflict = 0,
                prompt = null,
                handOff = null,
                edit = null,
                canUndo = false,
                canRedo = false,
            )
        }

        loadJob = viewModelScope.launch {
            val file = when (val result = getConflictedFileUseCase(path)) {
                is Either.Ok -> result.value.toResolverFile()
                is Either.Err -> ResolverFile.Failed(path, result.error)
            }

            state.update { it.copy(file = file) }
        }
    }

    private fun onStatus(conflicted: List<StatusEntry>) {
        this.conflicted = conflicted

        val shown = state.value.file.path
        val conflictedPaths = conflicted.map { it.filePath }.toSet()

        listFiles()

        // Renamed files show as paths with one side alone, and are read from the index
        if (conflicted.any { it.conflict?.ours == null || it.conflict?.theirs == null }) {
            viewModelScope.launch {
                val found = (getRenamedFilesUseCase() as? Either.Ok)?.value.orEmpty()

                renamed = found
                found.forEach { newNames[it.original] = listOf(it.ours, it.theirs) }
                listFiles()
            }
        } else {
            renamed = emptyList()
        }

        // A renamed file whose name was picked, once the rename is resolved: that name, if it's a conflict of its content
        val followed = follow

        if (followed != null && shown !in conflictedPaths) {
            follow = null

            if (followed in conflictedPaths) {
                load(followed)
                return
            }
        }

        // The first status after the merge tool: the file is still conflicted (load it again) or resolved (move on)
        val returnedFromTool = shown != null && shown == inExternalTool

        if (returnedFromTool) {
            inExternalTool = null
        }

        when (val next = afterStatus(shown, conflicted.map { it.filePath }, resolvedHere.isNotEmpty(), returnedFromTool)) {
            AfterStatus.Stay -> Unit

            is AfterStatus.Load -> load(next.path)

            // The view stays on the file, resolved, until the user opens the next one
            is AfterStatus.Resolved -> {
                val handOff = ResolverHandOff(
                    from = shown ?: return,
                    resolvedHere = resolvedHere.any { it.filePath == shown },
                    next = next.next,
                )

                state.update { it.copy(handOff = handOff) }
            }

            AfterStatus.Done -> state.update { it.copy(file = ResolverFile.None, done = true, handOff = null) }
            AfterStatus.Close -> closeNow()
        }
    }

    /**
     * The list: the conflicted files in the status pane's order, a renamed file once under its old name, then the files
     * resolved here.
     */
    private fun listFiles() {
        val conflictedPaths = conflicted.map { it.filePath }.toSet()
        val hidden = renamed.flatMap { listOf(it.ours, it.theirs) }.toSet()

        fun entry(status: StatusEntry, resolved: Boolean) =
            ResolverFileEntry(status, resolved, newNames[status.filePath].orEmpty())

        state.update { state ->
            state.copy(
                files = conflicted.filter { it.filePath !in hidden }.map { entry(it, resolved = false) } +
                    resolvedHere.filter { it.filePath !in conflictedPaths }.map { entry(it, resolved = true) },
            )
        }
    }

    override fun onClear() {
        loadJob?.cancel()
        super.onClear()
    }
}

/** The top pair's share of the stacked layout's height, until its handle is dragged. */
const val DEFAULT_STACKED_SPLIT = 0.45f

/**
 * What the resolver keeps between its views, until Leaf quits: the stacked layout's handle, the columns' and the file
 * list's widths, and Focus. The user chose not to save them (2026-10-12); saving them as the status pane's sections
 * are would take prefs keys. Wrap lines is a setting (Settings → Conflicts).
 */
@Singleton
class ConflictResolverSession @Inject constructor() {
    var stackedSplit = DEFAULT_STACKED_SPLIT
    var columnShares = ColumnShares()
    var fileListWidth = DEFAULT_FILE_LIST_WIDTH
    var focused = false
}

/** What a new status makes of the view. */
internal sealed interface AfterStatus {
    data object Stay : AfterStatus
    data class Load(val path: String) : AfterStatus

    /** The file shown has no conflicts any more: it stays until the user opens [next], the first conflicted file. */
    data class Resolved(val next: String) : AfterStatus

    /** Every conflicted file was resolved, some of them here: the view says so. */
    data object Done : AfterStatus
    data object Close : AfterStatus
}

/**
 * What the view does when the status lists the [conflicted] files: it opens the first one when it shows none, and stays
 * on [shown], resolved, once that has no conflicts (resolved here or elsewhere), with the first conflicted file next.
 * When none is left (all resolved, or the operation was aborted), it says so if something was [resolvedHere], and closes
 * otherwise. A file back from the external merge tool is loaded again ([returnedFromTool]), as the tool may have
 * changed it.
 */
internal fun afterStatus(
    shown: String?,
    conflicted: List<String>,
    resolvedHere: Boolean,
    returnedFromTool: Boolean,
): AfterStatus = when {
    shown == null -> conflicted.firstOrNull()?.let { AfterStatus.Load(it) } ?: AfterStatus.Stay
    shown in conflicted -> if (returnedFromTool) AfterStatus.Load(shown) else AfterStatus.Stay
    conflicted.isNotEmpty() -> AfterStatus.Resolved(next = conflicted.first())
    resolvedHere -> AfterStatus.Done
    else -> AfterStatus.Close
}

/**
 * The file shown, [from], has no conflicts any more: [resolvedHere] if it was marked resolved here (or a side was taken
 * for it), else something outside the resolver resolved it. The view stays on it, read-only, with a strip that says so,
 * until the user opens [next].
 */
data class ResolverHandOff(val from: String, val resolvedHere: Boolean, val next: String)

/** Where the user goes from a file: the view closes, another file opens, or something outside the view [then] runs. */
sealed interface ResolverLeave {
    data object Close : ResolverLeave
    data class OpenFile(val path: String) : ResolverLeave

    /** Closing the tab, or switching it to another worktree, which closes the view. */
    class Then(val then: () -> Unit) : ResolverLeave
}

/**
 * A part of the result being edited: [chunk]'s lines, as the text field has them ([value]), which started as [start].
 * [line]: the part's line where editing started, which the view keeps in its place, or null for its end.
 */
/** [syntax]: the field's colours (fork-only), maybe of its text before the last changes. */
data class ResolverEdit(
    val chunk: Int,
    val value: TextFieldValue,
    val start: EditStart,
    val line: Int?,
    val syntax: EditorSyntax? = null,
)

/** Where the caret goes in [text] when editing starts: at the end of line [line], or of the last line. */
internal fun caretAt(text: String, line: Int?): Int {
    if (line == null) {
        return text.length
    }

    var end = text.indexOf('\n')

    repeat(line) {
        if (end < 0) {
            return text.length
        }

        end = text.indexOf('\n', end + 1)
    }

    return if (end < 0) text.length else end
}

/** The most steps that Undo goes back. */
private const val MAX_UNDO_STEPS = 500

/**
 * How long after a change the part being edited is coloured again (fork-only), in milliseconds: typing faster than this
 * colours only the text that it stops at.
 */
private const val EDIT_COLOURING_DELAY = 80L

/**
 * The merges before each pick and finished edit of the file shown, for Undo, and the ones undone, for Redo. A new step
 * drops what Redo had. Loading a file starts over.
 */
private class ResolverHistory {
    private val undone = ArrayDeque<TextMerge>()
    private val redone = ArrayDeque<TextMerge>()

    val canUndo: Boolean @Synchronized get() = undone.isNotEmpty()
    val canRedo: Boolean @Synchronized get() = redone.isNotEmpty()

    @Synchronized
    fun record(before: TextMerge) {
        undone.addLast(before)

        if (undone.size > MAX_UNDO_STEPS) {
            undone.removeFirst()
        }

        redone.clear()
    }

    /** The merge before [current], which Redo then gets back. */
    @Synchronized
    fun undo(current: TextMerge): TextMerge? = undone.removeLastOrNull()?.also { redone.addLast(current) }

    @Synchronized
    fun redo(current: TextMerge): TextMerge? = redone.removeLastOrNull()?.also { undone.addLast(current) }

    @Synchronized
    fun clear() {
        undone.clear()
        redone.clear()
    }
}

data class ConflictResolverState(
    /** The conflicted files, in the status pane's order, then the ones resolved here. */
    val files: List<ResolverFileEntry> = emptyList(),
    val file: ResolverFile = ResolverFile.None,
    val layout: ConflictResolverLayout = ConflictResolverLayout.DEFAULT,
    /** The stacked layout's top pair's share of the height. */
    val stackedSplit: Float = DEFAULT_STACKED_SPLIT,
    /** The Columns layout's widths. */
    val columnShares: ColumnShares = ColumnShares(),
    /** The conflicted files' list, in dp. */
    val fileListWidth: Float = DEFAULT_FILE_LIST_WIDTH,
    /** Whether long lines wrap, or stay on one row and scroll sideways. */
    val wrapLines: Boolean = true,
    /** Focus: the tab bar, the repository's toolbar, the banner, the bottom bar and the file list are hidden. */
    val focused: Boolean = false,
    /** Whether open conflicts show the base's lines in the result. */
    val showBase: Boolean = false,
    /** The current conflict, among the file's conflicts. */
    val currentConflict: Int = 0,
    val prompt: ResolverPrompt? = null,
    /** The file shown was resolved, and the view waits to open the next one. */
    val handOff: ResolverHandOff? = null,
    /** Every conflicted file was resolved. */
    val done: Boolean = false,
    /** The part of the result being edited. */
    val edit: ResolverEdit? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** The downloads of Git LFS objects that the cards started and that are running or failed, by object id. */
    val lfsDownloads: Map<String, LfsDownload> = emptyMap(),
    /** The colours of the file's versions and of the lines typed in it, by their texts (fork-only). */
    val syntax: HighlightedVersions = HighlightedVersions(),
)

/** A Git LFS object's download from a choice card. One that ended well is gone, as the card shows the object. */
sealed interface LfsDownload {
    /** [received] bytes so far. */
    data class Running(val received: Long) : LfsDownload

    data class Failed(val error: AppError) : LfsDownload
}

/** What a card shows after a download failed: its Download again when the user closed the credentials dialog. */
internal fun lfsDownloadFailure(error: AppError): LfsDownload? =
    if ((error as? LfsObjectDownloadError)?.error == LfsError.CredentialsDenied) null else LfsDownload.Failed(error)

/** The file, with each of its versions that point to [version]'s LFS object shown as [version], once downloaded. */
internal fun ConflictedFile.withLfsVersion(version: VersionPreview.Lfs): ConflictedFile {
    val preview = preview ?: return this

    fun VersionPreview?.updated() = if (this is VersionPreview.Lfs && obj.oid == version.obj.oid) version else this

    return copy(
        preview = preview.copy(
            base = preview.base.updated(),
            ours = preview.ours.updated(),
            theirs = preview.theirs.updated(),
        ),
    )
}

/** A file of the list. [renamedTo]: a file renamed differently on each side, listed under its old name. */
data class ResolverFileEntry(
    val entry: StatusEntry,
    val resolvedHere: Boolean,
    val renamedTo: List<String> = emptyList(),
)

/** What a choice card picks: one side's version, or both new names of a file that each side renamed differently. */
enum class ChoicePick {
    OURS,
    THEIRS,
    BOTH;

    val sides: Set<ConflictSide>
        get() = when (this) {
            OURS -> setOf(ConflictSide.OURS)
            THEIRS -> setOf(ConflictSide.THEIRS)
            BOTH -> setOf(ConflictSide.OURS, ConflictSide.THEIRS)
        }

    companion object {
        fun of(side: ConflictSide) = if (side == ConflictSide.OURS) OURS else THEIRS
    }
}

sealed interface ResolverFile {
    val path: String?
    val hasUnsavedChanges: Boolean get() = false

    data object None : ResolverFile {
        override val path: String? = null
    }

    data class Loading(override val path: String) : ResolverFile

    data class Failed(override val path: String, val error: AppError) : ResolverFile

    /**
     * A conflicted file. [merge] has the picks, [saved] is what the working file has as the resolver last wrote or
     * loaded it; both are null for a conflict that three panes can't show, whose card picked is [pick].
     * [resolveAgain]: the working file had no markers, and the user chose to resolve it again.
     */
    data class Loaded(
        val file: ConflictedFile,
        val merge: TextMerge?,
        val saved: TextMerge?,
        val resolveAgain: Boolean = false,
        val pick: ChoicePick? = null,
    ) : ResolverFile {
        override val path: String get() = file.path
        override val hasUnsavedChanges: Boolean get() = merge != saved

        val workingFileState: WorkingFileState?
            get() = (file.content as? ConflictContent.Text)?.workingFile?.state
    }
}

/** What waits for the user's answer, in a strip above the columns. */
sealed interface ResolverPrompt {
    /** The file has changes that aren't saved, picks or lines typed, and the user is leaving it. */
    data class UnsavedChanges(val leave: ResolverLeave) : ResolverPrompt

    /** The working file changed since it was loaded: reload it, or [keep] the resolver's result over it. */
    data class WorkingFileChanged(val keep: () -> Unit) : ResolverPrompt

    data class Failed(val error: AppError) : ResolverPrompt
}

private fun ConflictedFile.toResolverFile(): ResolverFile.Loaded {
    // Long runs of unchanged lines are edited in parts
    val merge = (content as? ConflictContent.Text)?.merge?.inParts()

    return ResolverFile.Loaded(this, merge, merge)
}

/** A conflict that three panes can't show: a card per side to pick from. */
val ConflictedFile.hasChoiceCards: Boolean
    get() = content !is ConflictContent.Text

/** The file, as the working file is once Save wrote bytes with [contentId]. */
private fun ConflictedFile.withContentId(contentId: String): ConflictedFile {
    val text = content as? ConflictContent.Text ?: return this

    return copy(content = text.copy(workingFile = text.workingFile.copy(contentId = contentId)))
}
