// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.CleanupCandidate
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.WorktreeCleanup
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetWorktreeCleanupUseCase
import dev.app.leaf.domain.usecases.RunWorktreeStepsUseCase
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.candidates
import dev.app.leaf.domain.worktrees.checkedAtFirst
import dev.app.leaf.domain.worktrees.cleanupSteps
import dev.app.leaf.domain.worktrees.withOrigins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State of the "Clean up merged worktrees" dialog (fork-only, Phase 4.3b).
 *
 * @param cleanup what the dialog read when it opened, null while it reads it or when it couldn't.
 * @param checked the paths of the worktrees that the user checked.
 */
data class CleanUpWorktreesState(
    val cleanup: WorktreeCleanup? = null,
    val error: AppError? = null,
    val checked: Set<String> = emptySet(),
    val deleteBranches: Boolean = true,
) {
    val candidates: List<CleanupCandidate> = cleanup?.candidates.orEmpty()

    /** The worktrees that confirming removes, in the dialog's order. */
    val selected: List<CleanupCandidate>
        get() = candidates.filter { it.worktree.path in checked && !it.isInUse }

    /** Whether any candidate has a branch that "Also delete their branches" would delete. */
    val hasBranches: Boolean
        get() = candidates.any { it.branch != null && !it.isInUse }

    /** The steps that confirming runs, empty while nothing is checked. */
    val steps: List<WorktreeStep>
        get() {
            val main = cleanup?.list?.mainWorktree ?: return emptyList()

            return cleanupSteps(main, selected, deleteBranches)
        }

    val canConfirm: Boolean
        get() = steps.isNotEmpty()
}

/**
 * Removes the worktrees whose work is in the base and the ones whose folder is missing, the ones the user checked, and
 * their branches. It reads them again when it opens, as the tab's list may be stale.
 */
class CleanUpWorktreesViewModel @Inject constructor(
    private val getWorktreeCleanupUseCase: GetWorktreeCleanupUseCase,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
    private val appSettingsService: AppSettingsService,
) : TabViewModel() {
    val state: StateFlow<CleanUpWorktreesState>
        field = MutableStateFlow(CleanUpWorktreesState())

    init {
        viewModelScope.launch {
            state.value = when (val result = getWorktreeCleanupUseCase()) {
                is Either.Err -> CleanUpWorktreesState(error = result.error)
                is Either.Ok -> {
                    val rules = appSettingsService.worktreeLabels.first()
                    // Real paths read the file system
                    val list = withContext(Dispatchers.IO) { result.value.list.withOrigins(rules) }
                    val cleanup = result.value.copy(list = list)

                    CleanUpWorktreesState(cleanup = cleanup, checked = cleanup.candidates.checkedAtFirst())
                }
            }
        }
    }

    fun setChecked(candidate: CleanupCandidate, checked: Boolean) {
        if (candidate.isInUse) return

        val path = candidate.worktree.path

        state.update { it.copy(checked = if (checked) it.checked + path else it.checked - path) }
    }

    fun setDeleteBranches(deleteBranches: Boolean) {
        state.update { it.copy(deleteBranches = deleteBranches) }
    }

    /** Runs the dialog's steps, and returns true when they started, so that the dialog closes. */
    fun confirm(): Boolean {
        val current = state.value

        if (!current.canConfirm) {
            return false
        }

        runWorktreeStepsUseCase(TaskType.CleanUpWorktrees, current.steps)

        return true
    }
}
