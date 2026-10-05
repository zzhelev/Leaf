package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.exceptions.UncommittedChangesDetectedException
import dev.app.leaf.domain.interfaces.IMergeBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.ObjectId
import javax.inject.Inject


class MergeBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : IMergeBranchGitAction {
    /**
     * @return true if success has conflicts, false if success without conflicts
     */
    override suspend operator fun invoke(
        repositoryPath: String,
        branch: Branch,
        fastForward: Boolean,
    ) = jgit.provide(repositoryPath) { git ->

        val fastForwardMode = if (fastForward)
            MergeCommand.FastForwardMode.FF
        else
            MergeCommand.FastForwardMode.NO_FF

        val mergeBase: ObjectId = git.repository.resolve(branch.name) ?: throw Exception("Branch ${branch.name} not found")
        val currentBranch = git.repository.branch.orEmpty()

        val mergeResult = git
            .merge()
            .include(mergeBase)
            .setFastForward(fastForwardMode)
            .setMessage("Merge branch '${branch.simpleNameWithRemote}' into $currentBranch")
            .call()

        if (mergeResult.mergeStatus == MergeResult.MergeStatus.FAILED) {
            throw UncommittedChangesDetectedException("Merge failed, makes sure you repository doesn't contain uncommitted changes.")
        }

        val hasConflicts = mergeResult.mergeStatus == MergeResult.MergeStatus.CONFLICTING

        hasConflicts
    }
}