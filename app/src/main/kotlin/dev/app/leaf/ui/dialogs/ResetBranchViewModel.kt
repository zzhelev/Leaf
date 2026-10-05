package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.usecases.ResetBranchUseCase
import dev.app.leaf.domain.usecases.ResetType
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.eclipse.jgit.revwalk.RevCommit

class ResetBranchViewModel @AssistedInject constructor(
    private val resetBranchUseCase: ResetBranchUseCase,
    @Assisted private val targetCommit: Commit,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(commit: Commit): ResetBranchViewModel
    }

    fun reset(resetType: ResetType) {
        resetBranchUseCase(targetCommit, resetType)
    }
}