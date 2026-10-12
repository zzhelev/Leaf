// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.conflicts.ColumnShares
import dev.app.leaf.domain.conflicts.DEFAULT_FILE_LIST_WIDTH
import dev.app.leaf.domain.conflicts.applying
import dev.app.leaf.domain.conflicts.conflictChunks
import dev.app.leaf.domain.conflicts.dropping
import dev.app.leaf.domain.conflicts.resolving
import dev.app.leaf.domain.conflicts.taking
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetConflictedFileUseCase
import dev.app.leaf.domain.usecases.MarkConflictResolvedUseCase
import dev.app.leaf.domain.usecases.OpenMergeToolUseCase
import dev.app.leaf.domain.usecases.ResolveConflictUseCase
import dev.app.leaf.domain.usecases.SaveConflictResolutionUseCase
import dev.app.leaf.domain.usecases.StatusStageUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The built-in conflict resolver's whole-tab view (`docs/fork/conflict-resolver.md`, steps 2 and 3): the conflicted
 * files, and the one shown, with the picks made in it and the layout. Opened and closed by `RepositoryOpenViewModel`,
 * as file history is.
 *
 * The list follows the status: a file that stops being conflicted, because it was marked resolved here or resolved
 * elsewhere, gets a ✓ (if it was resolved here) and the next one opens. When none is left, the view says so, or closes
 * when nothing was resolved here.
 */
class ConflictResolverViewModel @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getConflictedFileUseCase: GetConflictedFileUseCase,
    private val saveConflictResolutionUseCase: SaveConflictResolutionUseCase,
    private val markConflictResolvedUseCase: MarkConflictResolvedUseCase,
    private val statusStageUseCase: StatusStageUseCase,
    private val resolveConflictUseCase: ResolveConflictUseCase,
    private val openMergeToolUseCase: OpenMergeToolUseCase,
    private val appSettingsService: AppSettingsService,
    private val session: ConflictResolverSession,
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

    private var onClose: () -> Unit = {}
    private var loadJob: Job? = null
    private var conflicted: List<StatusEntry> = emptyList()
    private val resolvedHere = mutableListOf<StatusEntry>()

    /** The file that the external merge tool has: it's loaded again once the status comes back with it conflicted. */
    private var inExternalTool: String? = null

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

    /** Closes the view, asking first when the file has picks that aren't saved. */
    fun close() = leave(ResolverLeave.Close)

    /** Esc: leaves Focus first, then closes the view (asking first when the file has picks that aren't saved). */
    fun escape() {
        if (state.value.focused) {
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

    private fun leave(leave: ResolverLeave) {
        if (state.value.file.hasUnsavedChanges) {
            state.update { it.copy(prompt = ResolverPrompt.UnsavedChanges(leave)) }
        } else {
            go(leave)
        }
    }

    private fun go(leave: ResolverLeave) = when (leave) {
        ResolverLeave.Close -> closeNow()
        is ResolverLeave.OpenFile -> load(leave.path)
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

    private fun updateMerge(change: (TextMerge) -> TextMerge) {
        state.update { state ->
            val file = state.file as? ResolverFile.Loaded ?: return@update state
            val merge = file.merge ?: return@update state

            state.copy(file = file.copy(merge = change(merge)))
        }
    }

    fun toggleBase() {
        state.update { it.copy(showBase = !it.showBase) }
    }

    /** Switches the layout, for every resolver in the app (Settings → Conflicts has the same choice). */
    fun setLayout(layout: ConflictResolverLayout) {
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

    /** Writes the result, markers for open conflicts included. [overwriteChanges]: over changes made since. */
    fun save(overwriteChanges: Boolean = false, then: () -> Unit = {}) {
        val file = state.value.file as? ResolverFile.Loaded ?: return
        val merge = file.merge ?: return

        viewModelScope.launch {
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

                is Either.Err -> showError(result.error, onKeep = { save(overwriteChanges = true, then) })
            }
        }
    }

    /** Saves and stages the file, once no conflict is open, then opens the next one. */
    fun markResolved(overwriteChanges: Boolean = false) {
        val file = state.value.file as? ResolverFile.Loaded ?: return
        val merge = file.merge ?: return

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
        val path = state.value.file.path ?: return

        markResolvedHere(path)
        statusStageUseCase(StatusEntry(path, StatusType.CONFLICTING))
    }

    /** A file without conflict markers: shows the merge, which Save writes over the file. */
    fun resolveAgain() = updateLoaded { it.copy(resolveAgain = true) }

    /** Loads the file again, dropping the picks. */
    fun reload() {
        val path = state.value.file.path ?: return

        state.update { it.copy(prompt = null) }
        load(path)
    }

    /** A conflict that three panes can't show: takes [side]'s version (asked for first by the view). */
    fun useSide(side: ConflictSide) {
        val path = state.value.file.path ?: return

        markResolvedHere(path)
        resolveConflictUseCase(listOf(path), side)
    }

    /** Hands the file to the external merge tool. It's loaded again when the tool is done. */
    fun openInExternalTool() {
        val path = state.value.file.path ?: return

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
        state.update { it.copy(file = ResolverFile.Loading(path), currentConflict = 0, prompt = null) }

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

        state.update { state ->
            state.copy(
                files = conflicted.map { ResolverFileEntry(it, resolvedHere = false) } +
                    resolvedHere.filter { it.filePath !in conflictedPaths }.map { ResolverFileEntry(it, resolvedHere = true) },
            )
        }

        // The first status after the merge tool: the file is still conflicted (load it again) or resolved (move on)
        val returnedFromTool = shown != null && shown == inExternalTool

        if (returnedFromTool) {
            inExternalTool = null
        }

        when (val next = afterStatus(shown, conflicted.map { it.filePath }, resolvedHere.isNotEmpty(), returnedFromTool)) {
            AfterStatus.Stay -> Unit
            is AfterStatus.Load -> load(next.path)
            AfterStatus.Done -> state.update { it.copy(file = ResolverFile.None, done = true) }
            AfterStatus.Close -> closeNow()
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

    /** Every conflicted file was resolved, some of them here: the view says so. */
    data object Done : AfterStatus
    data object Close : AfterStatus
}

/**
 * What the view does when the status lists the [conflicted] files: it opens the first one when it shows none, and moves
 * on from [shown] once that has no conflicts (resolved here or elsewhere, or the operation was aborted). When none is
 * left, it says so if something was [resolvedHere], and closes otherwise. A file back from the external merge tool is
 * loaded again ([returnedFromTool]), as the tool may have changed it.
 */
internal fun afterStatus(
    shown: String?,
    conflicted: List<String>,
    resolvedHere: Boolean,
    returnedFromTool: Boolean,
): AfterStatus = when {
    shown == null -> conflicted.firstOrNull()?.let { AfterStatus.Load(it) } ?: AfterStatus.Stay
    shown in conflicted -> if (returnedFromTool) AfterStatus.Load(shown) else AfterStatus.Stay
    conflicted.isNotEmpty() -> AfterStatus.Load(conflicted.first())
    resolvedHere -> AfterStatus.Done
    else -> AfterStatus.Close
}

/** Where the user goes from a file: the view closes, or another file opens. */
sealed interface ResolverLeave {
    data object Close : ResolverLeave
    data class OpenFile(val path: String) : ResolverLeave
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
    /** Every conflicted file was resolved. */
    val done: Boolean = false,
)

data class ResolverFileEntry(val entry: StatusEntry, val resolvedHere: Boolean)

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
     * loaded it; both are null for a conflict that three panes can't show. [resolveAgain]: the working file had no
     * markers, and the user chose to resolve it again.
     */
    data class Loaded(
        val file: ConflictedFile,
        val merge: TextMerge?,
        val saved: TextMerge?,
        val resolveAgain: Boolean = false,
    ) : ResolverFile {
        override val path: String get() = file.path
        override val hasUnsavedChanges: Boolean get() = merge != saved

        val workingFileState: WorkingFileState?
            get() = (file.content as? ConflictContent.Text)?.workingFile?.state
    }
}

/** What waits for the user's answer, in a strip above the columns. */
sealed interface ResolverPrompt {
    /** The file has picks that aren't saved, and the user is leaving it. */
    data class UnsavedChanges(val leave: ResolverLeave) : ResolverPrompt

    /** The working file changed since it was loaded: reload it, or [keep] the resolver's result over it. */
    data class WorkingFileChanged(val keep: () -> Unit) : ResolverPrompt

    data class Failed(val error: AppError) : ResolverPrompt
}

private fun ConflictedFile.toResolverFile(): ResolverFile.Loaded {
    val merge = (content as? ConflictContent.Text)?.merge

    return ResolverFile.Loaded(this, merge, merge)
}

/** The file, as the working file is once Save wrote bytes with [contentId]. */
private fun ConflictedFile.withContentId(contentId: String): ConflictedFile {
    val text = content as? ConflictContent.Text ?: return this

    return copy(content = text.copy(workingFile = text.workingFile.copy(contentId = contentId)))
}
