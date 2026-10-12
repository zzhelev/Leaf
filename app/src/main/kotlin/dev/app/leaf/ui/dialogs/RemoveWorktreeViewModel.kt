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
import dev.app.leaf.domain.models.WorktreeRemoval
import dev.app.leaf.domain.usecases.GetWorktreeRemovalUseCase
import dev.app.leaf.domain.usecases.RunWorktreeStepsUseCase
import dev.app.leaf.domain.worktrees.RemoveWorktreeOptions
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.removeSteps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the remove and discard worktree dialogs (fork-only).
 *
 * @param removal what removing the worktree would lose, null while it's read or when it couldn't be.
 * @param confirmingLoss discarding loses work, and the user confirmed once: the next confirmation discards.
 */
data class RemoveWorktreeState(
    val removal: WorktreeRemoval? = null,
    val error: AppError? = null,
    val options: RemoveWorktreeOptions = RemoveWorktreeOptions(),
    val confirmingLoss: Boolean = false,
) {
    /** The steps that confirming runs, empty until the worktree has been checked. */
    val steps: List<WorktreeStep>
        get() = removal?.removeSteps(options).orEmpty()

    /**
     * Whether confirming can run: git would refuse a worktree with changes without force, or a locked one without
     * unlocking it first.
     */
    val canConfirm: Boolean
        get() {
            val removal = removal ?: return false

            return (!removal.hasUncommittedChanges || options.force) && (!removal.isLocked || options.overrideLock)
        }

    /** Whether removing deletes work: uncommitted changes, or a branch with commits that the base doesn't have. */
    val losesWork: Boolean
        get() {
            val removal = removal ?: return false
            val deletesUnmergedBranch = options.deleteBranch && removal.branch != null &&
                removal.branchMerged?.isMerged != true

            return removal.hasUncommittedChanges || deletesUnmergedBranch
        }

    /** What confirming does in this state: nothing yet, ask a second time, or run the steps. */
    fun onConfirm(discard: Boolean): ConfirmOutcome = when {
        !canConfirm -> ConfirmOutcome.NOTHING
        discard && losesWork && !confirmingLoss -> ConfirmOutcome.ASK_AGAIN
        else -> ConfirmOutcome.RUN
    }
}

enum class ConfirmOutcome { NOTHING, ASK_AGAIN, RUN }

/**
 * The dialog's choices when it opens: removing asks for everything that loses work, and discarding has it all, which
 * the dialog doesn't let the user take back. Only unlocking is asked for both.
 */
fun initialRemoveOptions(removal: WorktreeRemoval, discard: Boolean): RemoveWorktreeOptions = if (discard) {
    RemoveWorktreeOptions(force = true, deleteBranch = removal.branch != null)
} else {
    RemoveWorktreeOptions()
}

/**
 * Removes a worktree once the user confirms, or discards it: removes it with its changes and deletes its branch. When
 * discarding loses work, the user confirms twice.
 */
class RemoveWorktreeViewModel @AssistedInject constructor(
    private val getWorktreeRemovalUseCase: GetWorktreeRemovalUseCase,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
    @Assisted val worktree: Worktree,
    @Assisted val discard: Boolean,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(worktree: Worktree, discard: Boolean): RemoveWorktreeViewModel
    }

    val state: StateFlow<RemoveWorktreeState>
        field = MutableStateFlow(RemoveWorktreeState())

    init {
        viewModelScope.launch {
            state.value = when (val result = getWorktreeRemovalUseCase(worktree)) {
                is Either.Err -> state.value.copy(error = result.error)
                is Either.Ok -> state.value.copy(
                    removal = result.value,
                    options = initialRemoveOptions(result.value, discard),
                )
            }
        }
    }

    fun setForce(force: Boolean) = updateOptions { it.copy(force = force || discard) }

    fun setOverrideLock(overrideLock: Boolean) = updateOptions { it.copy(overrideLock = overrideLock) }

    fun setDeleteBranch(deleteBranch: Boolean) = updateOptions { it.copy(deleteBranch = deleteBranch || discard) }

    private fun updateOptions(change: (RemoveWorktreeOptions) -> RemoveWorktreeOptions) {
        state.update { it.copy(options = change(it.options), confirmingLoss = false) }
    }

    /**
     * Runs the dialog's steps, and returns true when they started, so that the dialog closes. Discarding work asks a
     * second time first.
     */
    fun confirm(): Boolean {
        val current = state.value

        return when (current.onConfirm(discard)) {
            ConfirmOutcome.NOTHING -> false
            ConfirmOutcome.ASK_AGAIN -> {
                state.value = current.copy(confirmingLoss = true)
                false
            }

            ConfirmOutcome.RUN -> {
                val taskType = if (discard) TaskType.DiscardWorktree else TaskType.RemoveWorktree
                runWorktreeStepsUseCase(taskType, current.steps)
                true
            }
        }
    }
}
