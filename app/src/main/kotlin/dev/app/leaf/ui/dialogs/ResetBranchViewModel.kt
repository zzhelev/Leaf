package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.usecases.ResetBranchUseCase
import dev.app.leaf.domain.usecases.ResetType
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.extensions.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.eclipse.jgit.revwalk.RevCommit

class ResetBranchViewModel @AssistedInject constructor(
    private val resetBranchUseCase: ResetBranchUseCase,
    repositoryDataRepository: RepositoryDataRepository,
    @Assisted private val targetCommit: Commit,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(commit: Commit): ResetBranchViewModel
    }

    /** The files with uncommitted changes, staged or not, which a hard reset discards. */
    val changedFilesCount: StateFlow<Int> = repositoryDataRepository.status
        .map { state ->
            val status = state.dataOrNull() ?: return@map 0

            (status.staged + status.unstaged).map { it.filePath }.distinct().count()
        }
        .stateIn(0)

    fun reset(resetType: ResetType) {
        resetBranchUseCase(targetCommit, resetType)
    }
}