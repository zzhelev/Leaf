package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.usecases.CreateBranchUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.eclipse.jgit.revwalk.RevCommit


class CreateBranchViewModel @AssistedInject constructor(
    private val createBranchUseCase: CreateBranchUseCase,
    @Assisted private val commit: Commit?,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(commit: Commit?): CreateBranchViewModel
    }

    fun createBranch(branchName: String) {
        createBranchUseCase(branchName, commit)
    }
}