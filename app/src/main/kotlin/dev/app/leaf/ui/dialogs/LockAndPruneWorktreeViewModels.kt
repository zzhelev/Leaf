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
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.GetPrunableWorktreesUseCase
import dev.app.leaf.domain.usecases.RunWorktreeStepsUseCase
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.lockWorktreeSteps
import dev.app.leaf.domain.worktrees.pruneWorktreesSteps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The main worktree from the tab's worktree list, where worktree commands run, or null when there's no list. */
private suspend fun RepositoryDataRepository.mainWorktree(): Worktree? =
    worktrees.first { it !is DataState.Loading }.dataOrNull()?.mainWorktree

/** Locks a worktree with the reason the user gives (fork-only). */
class LockWorktreeViewModel @AssistedInject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
    @Assisted val worktree: Worktree,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(worktree: Worktree): LockWorktreeViewModel
    }

    /** The main worktree, where git runs, null until it's read. */
    val mainWorktree: StateFlow<Worktree?>
        field = MutableStateFlow(null)

    init {
        viewModelScope.launch { mainWorktree.value = repositoryDataRepository.mainWorktree() }
    }

    fun steps(reason: String): List<WorktreeStep> {
        val main = mainWorktree.value ?: return emptyList()

        return lockWorktreeSteps(worktree, main, reason)
    }

    fun lock(reason: String) {
        val steps = steps(reason)

        if (steps.isNotEmpty()) {
            runWorktreeStepsUseCase(TaskType.LockWorktree, steps, arrayOf(DataToRefresh.WORKTREES))
        }
    }
}

/**
 * State of the prune dialog (fork-only).
 *
 * @param prunable what git would prune, one line of git's per worktree; null while it's read.
 */
data class PruneWorktreesState(
    val prunable: List<String>? = null,
    val steps: List<WorktreeStep> = emptyList(),
    val error: AppError? = null,
) {
    val canPrune: Boolean
        get() = !prunable.isNullOrEmpty() && steps.isNotEmpty()
}

/** Prunes the worktrees whose folders are missing (fork-only), after showing what git would prune. */
class PruneWorktreesViewModel @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val getPrunableWorktreesUseCase: GetPrunableWorktreesUseCase,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
) : TabViewModel() {
    val state: StateFlow<PruneWorktreesState>
        field = MutableStateFlow(PruneWorktreesState())

    init {
        viewModelScope.launch {
            val main = repositoryDataRepository.mainWorktree()

            state.value = when (val result = getPrunableWorktreesUseCase()) {
                is Either.Err -> PruneWorktreesState(error = result.error)
                is Either.Ok -> PruneWorktreesState(
                    prunable = result.value,
                    steps = if (main != null) pruneWorktreesSteps(main) else emptyList(),
                )
            }
        }
    }

    fun prune() {
        if (state.value.canPrune) {
            runWorktreeStepsUseCase(TaskType.PruneWorktrees, state.value.steps, arrayOf(DataToRefresh.WORKTREES))
        }
    }
}
