package dev.app.leaf.data.git.branches

import dev.app.leaf.common.extensions.runIfNotNull
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.CreateBranchError
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.mapErr
import dev.app.leaf.domain.interfaces.ICreateBranchGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

class CreateBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : ICreateBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branchName: String, targetCommit: Commit?) =
        jgit.provide(repositoryPath) { git ->
            git
                .checkout()
                .setCreateBranch(true)
                .setName(branchName)
                .runIfNotNull(targetCommit) { commit ->
                    setStartPoint(commit.hash)
                }
                .call()

            Unit
        }.mapErr {
            if (it is GenericError) {
                if (it.message == "Ref $branchName already exists") {
                    CreateBranchError.BranchAlreadyExists(branchName)
                } else if (it.message == "Branch name $branchName is not allowed") {
                    CreateBranchError.NameNotAllowed(branchName)
                } else {
                    it
                }
            } else {
                it
            }
        }
}