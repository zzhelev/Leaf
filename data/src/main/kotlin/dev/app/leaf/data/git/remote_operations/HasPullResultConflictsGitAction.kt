package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.domain.interfaces.IHasPullResultConflictsGitAction
import dev.app.leaf.domain.interfaces.PullHasConflicts
import org.eclipse.jgit.api.PullResult
import javax.inject.Inject

class HasPullResultConflictsGitAction @Inject constructor() : IHasPullResultConflictsGitAction {
    override operator fun invoke(isRebase: Boolean, pullResult: PullResult): PullHasConflicts {
        return if (isRebase) {
            rebaseHasConflicts(pullResult.rebaseResult)
        } else {
            mergeHasConflicts(pullResult.mergeResult)
        }
    }
}
