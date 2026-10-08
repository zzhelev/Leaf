// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.usecases.DeleteBranchUseCase
import dev.app.leaf.domain.usecases.DeleteTagUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State of the delete branch and delete tag dialogs. [refusal] is set once git refused to delete the ref without force,
 * and confirming again then forces it.
 */
data class DeleteRefState(
    val refusal: DeleteRefError? = null,
    val error: AppError? = null,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
)

/** Deletes a branch or a tag once the user confirms: first without force, and with force after a second confirmation. */
abstract class DeleteRefViewModel : TabViewModel() {
    val state: StateFlow<DeleteRefState>
        field = MutableStateFlow(DeleteRefState())

    protected abstract suspend fun deleteRef(force: Boolean): Either<Unit, AppError>

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
    @Assisted val branch: Branch,
) : DeleteRefViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(branch: Branch): DeleteBranchViewModel
    }

    override suspend fun deleteRef(force: Boolean) = deleteBranchUseCase(branch, force)
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
