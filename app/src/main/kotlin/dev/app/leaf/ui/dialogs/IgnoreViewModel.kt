// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.ignore.IgnoreContext
import dev.app.leaf.domain.ignore.IgnoreFile
import dev.app.leaf.domain.ignore.IgnoreKind
import dev.app.leaf.domain.ignore.IgnoreOption
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.ignore.IgnoreRequest
import dev.app.leaf.domain.ignore.customRules
import dev.app.leaf.domain.ignore.ignoreOptions
import dev.app.leaf.domain.ignore.rulesToAdd
import dev.app.leaf.domain.usecases.AddIgnoreRulesUseCase
import dev.app.leaf.domain.usecases.GetIgnoreContextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the user picked to ignore: one of the request's [IgnoreOption]s, or a pattern they type. */
sealed interface IgnoreChoice {
    data class Way(val kind: IgnoreKind) : IgnoreChoice
    data object Custom : IgnoreChoice
}

/**
 * State of the ignore dialog (fork-only). [place] and [context] are null until the ignore files are read.
 * [customText] starts as the rules of the first option, so that the pattern field starts from what was selected.
 */
data class IgnoreState(
    val options: List<IgnoreOption>,
    val choice: IgnoreChoice,
    val customText: String,
    val place: IgnorePlace? = null,
    val context: IgnoreContext? = null,
    val error: AppError? = null,
) {
    /** What the choice writes, before the lines the file has already are left out. */
    val rules: List<String>
        get() = when (choice) {
            is IgnoreChoice.Way -> options.firstOrNull { it.kind == choice.kind }?.rules.orEmpty()
            IgnoreChoice.Custom -> customRules(customText)
        }

    /** The file of [place]. */
    val file: IgnoreFile?
        get() = place?.let { context?.files?.get(it) }

    /** The rules that the file doesn't have yet. */
    val toAdd: List<String>
        get() = file?.let { rulesToAdd(it.lines, rules) }.orEmpty()

    val canAdd: Boolean
        get() = file != null && file?.problem == null && toAdd.isNotEmpty()
}

/** Adds ignore rules for untracked files or a folder (fork-only), after the user picked what, and where. */
class IgnoreViewModel @AssistedInject constructor(
    private val getIgnoreContextUseCase: GetIgnoreContextUseCase,
    private val addIgnoreRulesUseCase: AddIgnoreRulesUseCase,
    @Assisted val request: IgnoreRequest,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(request: IgnoreRequest): IgnoreViewModel
    }

    val state: StateFlow<IgnoreState>
        field = MutableStateFlow(initialIgnoreState(request))

    init {
        viewModelScope.launch {
            when (val result = getIgnoreContextUseCase()) {
                is Either.Err -> state.value = state.value.copy(error = result.error)
                is Either.Ok -> state.value = state.value.copy(
                    context = result.value,
                    place = result.value.remembered,
                )
            }
        }
    }

    fun setChoice(choice: IgnoreChoice) {
        state.value = state.value.copy(choice = choice)
    }

    fun setCustomText(text: String) {
        state.value = state.value.copy(customText = text)
    }

    fun setPlace(place: IgnorePlace) {
        state.value = state.value.copy(place = place)
    }

    /** Adds the rules, as a task of the tab. Returns whether it did: false while there is nothing to add. */
    fun add(): Boolean {
        val current = state.value
        val place = current.place ?: return false

        if (!current.canAdd) return false

        addIgnoreRulesUseCase(place, current.rules)

        return true
    }
}

/**
 * The dialog's state for [request] before the ignore files are read: its first way to ignore picked, and the pattern
 * field starting from that way's rules, or empty when no way is offered.
 */
internal fun initialIgnoreState(request: IgnoreRequest): IgnoreState {
    val options = ignoreOptions(request)
    val first = options.firstOrNull()

    return IgnoreState(
        options = options,
        choice = first?.let { IgnoreChoice.Way(it.kind) } ?: IgnoreChoice.Custom,
        customText = first?.rules?.joinToString("\n").orEmpty(),
    )
}
