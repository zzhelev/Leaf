// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleLibraryError
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.domain.styles.StylesService
import dev.app.leaf.system.SaveFilePickerUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the style editor was opened on (fork-only). */
sealed interface StyleEditorStart {
    /** A style of the user's, as its file has it. */
    data class Edit(val style: StyleDefinition) : StyleEditorStart

    /** A new style named [name], with [from]'s colours: "+ New style" and Duplicate. [fromName] is shown. */
    data class From(val from: StyleDefinition, val name: String, val fromName: String) : StyleEditorStart
}

/** What the editor asks for. */
sealed interface StyleEditorAction {
    data class Rename(val name: String) : StyleEditorAction
    data class SetLook(val look: AccentStyle) : StyleEditorAction
    data class SetSeed(val seed: StyleSeed, val argb: Int, val isLight: Boolean? = null) : StyleEditorAction
    data class SetColour(val slot: StyleSlot, val isLight: Boolean, val argb: Int) : StyleEditorAction
    data class UseWorkedOutColour(val slot: StyleSlot, val isLight: Boolean) : StyleEditorAction
    data class OpenPicker(val target: ColourTarget?) : StyleEditorAction
    data class SetAdvancedOpen(val isOpen: Boolean) : StyleEditorAction
    data object Revert : StyleEditorAction
    data object Save : StyleEditorAction
    data class Export(val path: String) : StyleEditorAction
    /** After the user confirmed it. */
    data object Delete : StyleEditorAction
    data object DismissMessage : StyleEditorAction
}

/** The outcome of an export or a failed save, shown in the editor's footer until dismissed. */
sealed interface StyleEditorMessage {
    data class Exported(val path: String) : StyleEditorMessage
    data class Failed(val error: StyleLibraryError) : StyleEditorMessage
}

/** Edits a style of the user's, or makes a new one (fork-only, `docs/fork/custom-styles.md` 5.2). */
class StyleEditorViewModel @AssistedInject constructor(
    private val stylesService: StylesService,
    private val saveFilePickerUseCase: SaveFilePickerUseCase,
    @Assisted start: StyleEditorStart,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(start: StyleEditorStart): StyleEditorViewModel
    }

    val state: StateFlow<StyleEditorState>
        field = MutableStateFlow(
            when (start) {
                is StyleEditorStart.Edit -> StyleEditorState.editing(start.style)
                // The id is given when it's saved, from the name it has then
                is StyleEditorStart.From -> StyleEditorState.startingFrom(start.from, id = "", start.name, start.fromName)
            }
        )

    val message: StateFlow<StyleEditorMessage?>
        field = MutableStateFlow(null)

    /** Set once the style was saved or deleted: the dialog closes. */
    val isDone: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private var job: Job? = null

    /** Asks where to export, on the thread that clicked, as the pickers are modal. */
    fun pickExportPath(title: String, fileName: String): String? = saveFilePickerUseCase(title, fileName)

    fun onAction(action: StyleEditorAction) {
        when (action) {
            is StyleEditorAction.Rename -> state.update { it.renamed(action.name) }
            is StyleEditorAction.SetLook -> state.update { it.withLook(action.look) }
            is StyleEditorAction.SetSeed -> state.update { it.withSeed(action.seed, action.argb, action.isLight) }
            is StyleEditorAction.SetColour -> state.update { it.withColourSetByHand(action.slot, action.isLight, action.argb) }
            is StyleEditorAction.UseWorkedOutColour -> state.update { it.withWorkedOutColour(action.slot, action.isLight) }
            is StyleEditorAction.OpenPicker -> state.update { it.withPicker(action.target) }
            is StyleEditorAction.SetAdvancedOpen -> state.update { it.withAdvancedOpen(action.isOpen) }
            StyleEditorAction.Revert -> state.update { it.reverted() }
            StyleEditorAction.DismissMessage -> message.value = null
            StyleEditorAction.Save -> run { save() }
            is StyleEditorAction.Export -> run { export(File(action.path)) }
            StyleEditorAction.Delete -> run { delete() }
        }
    }

    /** One write at a time: a second click on Save while the first writes does nothing. */
    private fun run(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch { block() }
    }

    private suspend fun save() {
        val current = state.value
        val name = current.draft.name.trim()
        if (name.isEmpty()) return

        val style = if (current.isNew) {
            current.draft.copy(id = stylesService.newId(name), name = name)
        } else {
            current.draft.copy(name = name)
        }

        when (val result = stylesService.save(style)) {
            is Either.Err -> message.value = StyleEditorMessage.Failed(result.error)
            is Either.Ok -> {
                // A new style is what the user made it for: draw Leaf with it
                if (current.isNew) stylesService.select(style.id)
                state.update { StyleEditorState.editing(style) }
                isDone.value = true
            }
        }
    }

    private suspend fun export(file: File) {
        message.value = when (val result = stylesService.export(state.value.draft, file)) {
            is Either.Ok -> StyleEditorMessage.Exported(file.absolutePath)
            is Either.Err -> StyleEditorMessage.Failed(result.error)
        }
    }

    private suspend fun delete() {
        val current = state.value
        if (current.isNew) return

        when (val result = stylesService.delete(current.saved)) {
            is Either.Err -> message.value = StyleEditorMessage.Failed(result.error)
            is Either.Ok -> isDone.value = true
        }
    }
}
