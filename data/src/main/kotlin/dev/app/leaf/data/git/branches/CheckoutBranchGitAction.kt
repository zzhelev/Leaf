package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.worktrees.refuseIfUsedByOtherWorktree
import dev.app.leaf.domain.interfaces.ICheckoutBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

class CheckoutBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : ICheckoutBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch) = jgit.provide(repositoryPath) { git ->
        // JGit would check out a branch that another worktree has, unlike git
        if (branch.name.startsWith(Constants.R_HEADS)) {
            refuseIfUsedByOtherWorktree(git.repository, branch.name)
        }

        git.checkout().apply {
            setName(branch.name)
            if (branch.name.startsWith("refs/remotes/")) {
                setCreateBranch(true)
                setName(branch.simpleName)
                setStartPoint(branch.name)
                setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
            }
            call()
        }

        Unit
    }
}