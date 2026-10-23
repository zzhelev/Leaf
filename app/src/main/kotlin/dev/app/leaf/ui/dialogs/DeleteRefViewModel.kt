// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.branches.BranchCopy
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.usecases.DeleteBranchUseCase
import dev.app.leaf.domain.usecases.DeleteRemoteBranchUseCase
import dev.app.leaf.domain.usecases.DeleteTagUseCase
import dev.app.leaf.domain.usecases.GetBranchDeletionPlanUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the delete branch and delete tag dialogs. [refusal] is set once git refused to delete the ref without force,
 * and confirming again then forces it.
 *
 * @param remoteCopy a branch's copy on its remote, its upstream, when it can go with the branch: any but the remote's
 * `HEAD` and default branch, which servers refuse to delete (fork-only).
 * @param deletesCopy the user asked for [remoteCopy] to go too, on the server, once the branch is deleted.
 */
data class DeleteRefState(
    val refusal: DeleteRefError? = null,
    val error: AppError? = null,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val remoteCopy: BranchCopy? = null,
    val deletesCopy: Boolean = false,
)

/** Deletes a branch or a tag once the user confirms: first without force, and with force after a second confirmation. */
abstract class DeleteRefViewModel : TabViewModel() {
    val state: StateFlow<DeleteRefState>
        field = MutableStateFlow(DeleteRefState())

    protected abstract suspend fun deleteRef(force: Boolean): Either<Unit, AppError>

    fun setDeletesCopy(on: Boolean) = state.update { if (it.remoteCopy == null || it.isDeleting) it else it.copy(deletesCopy = on) }

    /** Offers to delete a branch's [copy] on its remote with it. */
    protected fun offerCopy(copy: BranchCopy) = state.update { it.copy(remoteCopy = copy) }

    fun delete() {
        val current = state.value

        if (current.isDeleting || current.isDeleted) {
            return
        }

        state.value = current.copy(error = null, isDeleting = true)

        viewModelScope.launch {
            val result = deleteRef(force = current.refusal != null)

            state.value = when (result) {
                is Either.Ok -> state.value.copy(isDeleting = false, isDeleted = true)
                is Either.Err -> when (val error = result.error) {
                    is DeleteRefError -> state.value.copy(refusal = error, isDeleting = false)
                    else -> state.value.copy(error = error, isDeleting = false)
                }
            }
        }
    }
}

class DeleteBranchViewModel @AssistedInject constructor(
    private val deleteBranchUseCase: DeleteBranchUseCase,
    private val getBranchDeletionPlanUseCase: GetBranchDeletionPlanUseCase,
    private val deleteRemoteBranchUseCase: DeleteRemoteBranchUseCase,
    @Assisted val branch: Branch,
) : DeleteRefViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(branch: Branch): DeleteBranchViewModel
    }

    init {
        // Its copy on its remote, which the dialog offers to delete too when it's merged there
        viewModelScope.launch {
            val plan = getBranchDeletionPlanUseCase(BranchesDeletionRequest(listOf(branch)))
            val copy = (plan as? Either.Ok)?.value?.copies?.get(branch.name)?.takeIf { it.goes }

            if (copy != null) offerCopy(copy)
        }
    }

    /**
     * Deletes the branch, then its copy when asked, as the single remote delete does: a task of its own, with the lease,
     * whose failure opens the error dialog, as this one has closed by then.
     */
    override suspend fun deleteRef(force: Boolean): Either<Unit, AppError> {
        val result = deleteBranchUseCase(branch, force)
        val copy = state.value.remoteCopy?.takeIf { state.value.deletesCopy }

        if (result is Either.Ok && copy != null) {
            deleteRemoteBranchUseCase(Branch(hash = copy.info.branch.tip, name = copy.info.branch.name, isLocal = false))
        }

        return result
    }
}

class DeleteTagViewModel @AssistedInject constructor(
    private val deleteTagUseCase: DeleteTagUseCase,
    @Assisted val tag: Tag,
) : DeleteRefViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(tag: Tag): DeleteTagViewModel
    }

    override suspend fun deleteRef(force: Boolean) = deleteTagUseCase(tag, force)
}
