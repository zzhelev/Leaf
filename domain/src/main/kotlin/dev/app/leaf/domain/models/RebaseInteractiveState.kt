package dev.app.leaf.domain.models

sealed interface RebaseInteractiveState {
    data object None : RebaseInteractiveState
    data class ProcessingCommits(val commitToAmendId: String?) : RebaseInteractiveState {
        val isCurrentStepAmenable: Boolean = !commitToAmendId.isNullOrBlank()
    }
}