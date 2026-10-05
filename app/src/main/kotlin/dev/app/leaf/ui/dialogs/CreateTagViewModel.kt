package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.usecases.CreateTagUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.eclipse.jgit.revwalk.RevCommit

class CreateTagViewModel @AssistedInject constructor(
    private val createTagUseCase: CreateTagUseCase,
    @Assisted private val targetCommit: Commit,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(commit: Commit): CreateTagViewModel
    }

    fun createTag(name: String) {
        createTagUseCase(name, targetCommit)
    }
}