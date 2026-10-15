package dev.app.leaf.domain.models

import dev.app.leaf.domain.errors.AppError

sealed interface ViewDiffResult {
    val diffType: DiffType?

    object None : ViewDiffResult {
        override val diffType: DiffType? = null
    }

    data class Loading(override val diffType: DiffType) : ViewDiffResult

    data class DiffNotFound(override val diffType: DiffType?) : ViewDiffResult

    data class Loaded(override val diffType: DiffType, val diffResult: DiffResult) : ViewDiffResult

    /**
     * The diff couldn't be loaded, as [error] says, and the pane says so rather than closing (fork-only): a file that a
     * partial clone couldn't download. Null [diffType] when even the commit's files couldn't be listed.
     */
    data class Failed(override val diffType: DiffType?, val error: AppError) : ViewDiffResult
}