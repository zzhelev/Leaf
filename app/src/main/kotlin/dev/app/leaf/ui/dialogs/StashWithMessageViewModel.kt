package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.usecases.ResetBranchUseCase
import dev.app.leaf.domain.usecases.ResetType
import dev.app.leaf.domain.usecases.StashChangesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.eclipse.jgit.revwalk.RevCommit
import javax.inject.Inject

class StashWithMessageViewModel @Inject constructor(
    private val stashChangesUseCase: StashChangesUseCase,
) : TabViewModel() {

    fun stash(message: String) {
        stashChangesUseCase(message)
    }
}