package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IDeleteLocallyRemoteBranchesGitAction
import javax.inject.Inject

class DeleteLocallyRemoteBranchesGitAction @Inject constructor(private val jgit: JGit) :
    IDeleteLocallyRemoteBranchesGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        branches: List<String>
    ) = jgit.provide(repositoryPath) { git ->
        git
            .branchDelete()
            .setBranchNames(*branches.toTypedArray())
            .setForce(true)
            .call()
    }
}