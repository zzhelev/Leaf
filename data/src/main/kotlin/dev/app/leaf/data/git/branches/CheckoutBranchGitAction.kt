package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICheckoutBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.errors.RefAlreadyExistsException
import javax.inject.Inject

class CheckoutBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : ICheckoutBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch) = jgit.provide(repositoryPath) { git ->
        try {
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
        } catch (_: RefAlreadyExistsException) {
            // Only creating the local branch of a remote one throws it
            raiseError(
                CheckoutBranchError.LocalBranchAlreadyExists(
                    localBranch = branch.simpleName,
                    remoteBranch = branch.simpleNameWithRemote,
                )
            )
        }

        Unit
    }
}
