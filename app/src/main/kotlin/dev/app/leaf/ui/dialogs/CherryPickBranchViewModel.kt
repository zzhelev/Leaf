// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.cherrypick.BranchCherryPick
import dev.app.leaf.domain.cherrypick.CherryPickPlan
import dev.app.leaf.domain.cherrypick.CherryPickRefusal
import dev.app.leaf.domain.cherrypick.cherryPickPlan
import dev.app.leaf.domain.cherrypick.picked
import dev.app.leaf.domain.cherrypick.refusal
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.usecases.CherryPickCommitsUseCase
import dev.app.leaf.domain.usecases.GetBranchCherryPickUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the dialog that cherry-picks a branch's commits onto the current branch (fork-only).
 *
 * @param pick what the cherry-pick needs, null while it's read or when it couldn't be.
 * @param selected the commits ticked, by hash. Every commit that can be picked is, at first.
 */
data class CherryPickBranchState(
    val pick: BranchCherryPick? = null,
    val error: AppError? = null,
    val selected: Set<String> = emptySet(),
    val asOneCommit: Boolean = false,
) {
    /** Why the button is disabled, null when it isn't or before the commits have been read. */
    val refusal: CherryPickRefusal?
        get() = pick?.refusal(selected)

    /** What confirming runs, which the commands show; null while there's nothing to pick. */
    val plan: CherryPickPlan?
        get() = pick?.cherryPickPlan(selected, asOneCommit)

    /** How many commits confirming picks. */
    val pickedCount: Int
        get() = pick?.picked(selected)?.size ?: 0

    val canConfirm: Boolean
        get() = pick != null && refusal == null && plan != null
}

/** Cherry-picks the commits of [branch] that the user ticked onto the current branch once the user confirms. */
class CherryPickBranchViewModel @AssistedInject constructor(
    private val getBranchCherryPickUseCase: GetBranchCherryPickUseCase,
    private val cherryPickCommitsUseCase: CherryPickCommitsUseCase,
    @Assisted val branch: Branch,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(branch: Branch): CherryPickBranchViewModel
    }

    val state: StateFlow<CherryPickBranchState>
        field = MutableStateFlow(CherryPickBranchState())

    init {
        viewModelScope.launch {
            state.value = when (val result = getBranchCherryPickUseCase(branch)) {
                is Either.Err -> state.value.copy(error = result.error)
                is Either.Ok -> state.value.copy(
                    pick = result.value,
                    selected = result.value.pickable.map { it.hash }.toSet(),
                )
            }
        }
    }

    /** Ticks or unticks the commit [hash]. A commit that can't be picked stays unticked. */
    fun toggle(hash: String) {
        state.update { current ->
            val canPick = current.pick?.pickable?.any { it.hash == hash } == true

            when {
                !canPick -> current
                hash in current.selected -> current.copy(selected = current.selected - hash)
                else -> current.copy(selected = current.selected + hash)
            }
        }
    }

    fun setAsOneCommit(asOneCommit: Boolean) {
        state.update { it.copy(asOneCommit = asOneCommit) }
    }

    /** Runs the plan, and returns true when it started, so that the dialog closes. */
    fun confirm(): Boolean {
        val current = state.value
        val plan = current.plan

        if (!current.canConfirm || plan == null) {
            return false
        }

        cherryPickCommitsUseCase(plan)

        return true
    }
}
