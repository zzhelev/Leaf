package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IDeleteBranchGitAction
import dev.app.leaf.domain.models.Branch
import javax.inject.Inject

class DeleteBranchGitAction @Inject constructor(private val jgit: JGit) : IDeleteBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch) = jgit.provide(repositoryPath) { git ->
        git
            .branchDelete()
            .setBranchNames(branch.name)
            .setForce(true) // TODO Should it be forced?
            .call()

        Unit
    }
}