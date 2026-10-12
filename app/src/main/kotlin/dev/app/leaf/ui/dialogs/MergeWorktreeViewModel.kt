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
import dev.app.leaf.domain.models.WorktreeMerge
import dev.app.leaf.domain.models.WorktreeMergeKind
import dev.app.leaf.domain.models.WorktreeMergeRefusal
import dev.app.leaf.domain.usecases.GetWorktreeMergeUseCase
import dev.app.leaf.domain.usecases.RunWorktreeStepsUseCase
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.mergeSteps
import dev.app.leaf.domain.worktrees.refusal
import dev.app.leaf.domain.worktrees.squashMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State of the dialog that squash-merges or merges a worktree's branch into the base (fork-only).
 *
 * @param merge what the merge needs, null while it's read or when it couldn't be.
 */
data class MergeWorktreeState(
    val kind: WorktreeMergeKind,
    val merge: WorktreeMerge? = null,
    val error: AppError? = null,
) {
    /** Why the merge can't run, null when it can or before the worktrees have been checked. */
    val refusal: WorktreeMergeRefusal?
        get() = merge?.refusal(kind)

    /** The squash commit's message, which goes to `SQUASH_MSG`. Null for a merge, whose message is git's. */
    val squashMessage: String?
        get() = merge?.takeIf { kind == WorktreeMergeKind.SQUASH }?.let {
            squashMessage(it.branch, it.commits, it.commitCount)
        }

    /** The steps that confirming runs, empty until the worktrees have been checked. */
    val steps: List<WorktreeStep>
        get() = merge?.mergeSteps(kind, squashMessage.orEmpty()).orEmpty()

    val canConfirm: Boolean
        get() = merge != null && refusal == null
}

/** Squash-merges or merges a worktree's branch into the base's local branch once the user confirms. */
class MergeWorktreeViewModel @AssistedInject constructor(
    private val getWorktreeMergeUseCase: GetWorktreeMergeUseCase,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
    @Assisted val worktree: Worktree,
    @Assisted val kind: WorktreeMergeKind,
    @Assisted val target: String,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(worktree: Worktree, kind: WorktreeMergeKind, target: String): MergeWorktreeViewModel
    }

    val state: StateFlow<MergeWorktreeState>
        field = MutableStateFlow(MergeWorktreeState(kind))

    init {
        viewModelScope.launch {
            state.value = when (val result = getWorktreeMergeUseCase(worktree)) {
                is Either.Err -> state.value.copy(error = result.error)
                is Either.Ok -> state.value.copy(merge = result.value)
            }
        }
    }

    /** Runs the dialog's steps, and returns true when they started, so that the dialog closes. */
    fun confirm(): Boolean {
        val current = state.value

        if (!current.canConfirm) {
            return false
        }

        val taskType = when (kind) {
            WorktreeMergeKind.SQUASH -> TaskType.SquashMergeWorktree
            WorktreeMergeKind.MERGE -> TaskType.MergeWorktree
        }

        runWorktreeStepsUseCase(taskType, current.steps)

        return true
    }
}
