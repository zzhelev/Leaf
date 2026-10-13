// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRebase
import dev.app.leaf.domain.models.WorktreeRebaseRefusal
import dev.app.leaf.domain.usecases.GetWorktreeRebaseUseCase
import dev.app.leaf.domain.usecases.RunWorktreeStepsUseCase
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.rebaseSteps
import dev.app.leaf.domain.worktrees.refusal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State of the dialog that rebases a worktree's branch onto the base (fork-only).
 *
 * @param rebase what the rebase needs, null while it's read or when it couldn't be.
 */
data class RebaseWorktreeState(
    val rebase: WorktreeRebase? = null,
    val error: AppError? = null,
) {
    /** Why the rebase can't run, null when it can or before the worktree has been checked. */
    val refusal: WorktreeRebaseRefusal?
        get() = rebase?.refusal()

    /** The steps that confirming runs, empty until the worktree has been checked. */
    val steps: List<WorktreeStep>
        get() = rebase?.rebaseSteps().orEmpty()

    val canConfirm: Boolean
        get() = rebase != null && refusal == null
}

/** Rebases a worktree's branch onto the base once the user confirms. */
class RebaseWorktreeViewModel @AssistedInject constructor(
    private val getWorktreeRebaseUseCase: GetWorktreeRebaseUseCase,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
    @Assisted val worktree: Worktree,
    @Assisted val base: String,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(worktree: Worktree, base: String): RebaseWorktreeViewModel
    }

    val state: StateFlow<RebaseWorktreeState>
        field = MutableStateFlow(RebaseWorktreeState())

    init {
        viewModelScope.launch {
            state.value = when (val result = getWorktreeRebaseUseCase(worktree)) {
                is Either.Err -> state.value.copy(error = result.error)
                is Either.Ok -> state.value.copy(rebase = result.value)
            }
        }
    }

    /** Runs the dialog's steps, and returns true when they started, so that the dialog closes. */
    fun confirm(): Boolean {
        val current = state.value

        if (!current.canConfirm) {
            return false
        }

        runWorktreeStepsUseCase(TaskType.RebaseWorktree, current.steps)

        return true
    }
}
