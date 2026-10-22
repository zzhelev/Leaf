// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.branches.toDelete
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.usecases.DeleteBranchesUseCase
import dev.app.leaf.domain.usecases.GetBranchDeletionPlanUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the "Delete branches" dialog (fork-only, upstream issue 116).
 *
 * @param plan what the dialog read when it opened, null while it reads it or when it couldn't.
 * @param checked the full names of the branches that the user checked.
 * @param result what deleting did to each branch, once it ran. The dialog then shows it instead of the list.
 * @param error why the plan couldn't be read, or why nothing could be deleted: the repository couldn't be opened, or
 * for a remote's branches, git couldn't run or reach the server.
 * @param wasStopped the user cancelled deleting a remote's branches, after which the server may have deleted some.
 */
data class DeleteBranchesState(
    val request: BranchesDeletionRequest,
    val plan: BranchDeletionPlan? = null,
    val checked: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
    val result: BranchesDeletion? = null,
    val error: AppError? = null,
    val wasStopped: Boolean = false,
) {
    /** The branches that confirming deletes, in the dialog's order. */
    val toDelete: List<BranchToDelete>
        get() = plan?.toDelete(checked).orEmpty()

    val canConfirm: Boolean
        get() = plan != null && result == null && !isDeleting && !wasStopped && toDelete.isNotEmpty()

    /**
     * Whether one of the checked branches isn't merged, its commits on other branches or not, which for a remote's may
     * have a pull request open.
     */
    val checksUnmerged: Boolean
        get() = plan?.let { plan ->
            plan.rows.any { it.branch.name in checked && plan.kindOf(it) !is BranchDeletionKind.Merged }
        } == true

    /** The list can't change while it deletes, nor once the result is shown, nor after a stop, which may have deleted. */
    private val isLocked: Boolean
        get() = plan == null || isDeleting || result != null || wasStopped

    /** [name] checked or unchecked. A branch that can't be deleted, and a name the list doesn't have, stay as they are. */
    fun withChecked(name: String, isChecked: Boolean): DeleteBranchesState {
        val plan = plan ?: return this
        val info = plan.rows.firstOrNull { it.branch.name == name }

        return when {
            info == null || isLocked || !plan.canCheck(info) -> this
            isChecked -> copy(checked = checked + name)
            else -> copy(checked = checked - name)
        }
    }

    /** "All": every branch that can be deleted. */
    fun withAllChecked(): DeleteBranchesState = checkedWhere { plan, info -> plan.canCheck(info) }

    /** "Safe": the ones checked when the dialog opens, which lose nothing and, for a remote's, don't look long-lived. */
    fun withSafeChecked(): DeleteBranchesState =
        checkedWhere { plan, info -> plan.canCheck(info) && plan.isCheckedAtFirst(info) }

    fun withNoneChecked(): DeleteBranchesState = checkedWhere { _, _ -> false }

    private fun checkedWhere(predicate: (BranchDeletionPlan, BranchDeletionInfo) -> Boolean): DeleteBranchesState {
        val plan = plan ?: return this

        return if (isLocked) {
            this
        } else {
            copy(checked = plan.rows.filter { predicate(plan, it) }.map { it.branch.name }.toSet())
        }
    }
}

/**
 * Reads what deleting [request]'s branches would do, lets the user check them, deletes the checked ones and keeps the
 * result for the dialog to show. It reads again when it opens, as the tab's branch list may be stale.
 */
class DeleteBranchesViewModel @AssistedInject constructor(
    private val getBranchDeletionPlanUseCase: GetBranchDeletionPlanUseCase,
    private val deleteBranchesUseCase: DeleteBranchesUseCase,
    @Assisted request: BranchesDeletionRequest,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(request: BranchesDeletionRequest): DeleteBranchesViewModel
    }

    val state: StateFlow<DeleteBranchesState>
        field = MutableStateFlow(DeleteBranchesState(request))

    init {
        viewModelScope.launch {
            when (val result = getBranchDeletionPlanUseCase(request)) {
                is Either.Err -> state.update { it.copy(error = result.error) }
                is Either.Ok -> state.update {
                    it.copy(plan = result.value, checked = result.value.checkedAtFirst())
                }
            }
        }
    }

    fun setChecked(name: String, isChecked: Boolean) = state.update { it.withChecked(name, isChecked) }

    fun checkAll() = state.update { it.withAllChecked() }

    fun checkSafe() = state.update { it.withSafeChecked() }

    fun checkNone() = state.update { it.withNoneChecked() }

    private var deleting: Job? = null

    /** Deletes the checked branches. The dialog stays open and shows what happened to each. */
    fun delete() {
        val current = state.value

        if (!current.canConfirm) return

        state.update { it.copy(isDeleting = true, error = null) }

        deleting = viewModelScope.launch {
            try {
                when (val result = deleteBranchesUseCase(current.toDelete, current.request.remote)) {
                    is Either.Err -> state.update { it.copy(isDeleting = false, error = result.error) }
                    is Either.Ok -> state.update { it.copy(isDeleting = false, result = result.value) }
                }
            } catch (e: CancellationException) {
                state.update { it.copy(isDeleting = false, wasStopped = true) }
                throw e
            }
        }
    }

    /** Stops deleting a remote's branches: git is killed, and the server may have deleted some by then. */
    fun stopDeleting() {
        if (state.value.request.remote == null) return

        deleting?.cancel()
    }
}
