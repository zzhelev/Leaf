package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.countCommitsOnlyOn
import dev.app.leaf.data.git.worktrees.refuseDeletingIfUsedByWorktree
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDeleteBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.errors.NotMergedException
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

class DeleteBranchGitAction @Inject constructor(private val jgit: JGit) : IDeleteBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch, force: Boolean) =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            fun notMerged() = DeleteRefError.BranchNotMerged(
                branchName = branch.simpleName,
                commitsOnlyOnRef = repository.countCommitsOnlyOn(branch.name),
            )

            // JGit deletes a branch that another worktree uses, unlike git, even with force. That worktree would be
            // left on a branch that doesn't exist. git checks this before the merge, so -D doesn't get past it.
            if (branch.name.startsWith(Constants.R_HEADS)) {
                refuseDeletingIfUsedByWorktree(repository, branch.name)
            }

            // JGit's merge check throws a NullPointerException when HEAD is unborn. Git refuses -d then too.
            if (!force && repository.resolve(Constants.HEAD) == null) {
                raiseError(notMerged())
            }

            try {
                git
                    .branchDelete()
                    .setBranchNames(branch.name)
                    .setForce(force)
                    .call()
            } catch (_: NotMergedException) {
                raiseError(notMerged())
            }

            Unit
        }
}