package dev.app.leaf.data.git.branches

import dev.app.leaf.common.extensions.runIfNotNull
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.CreateBranchError
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.mapErr
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICreateBranchGitAction
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

class CreateBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val sparseCheckout: SparseCheckoutGit,
) : ICreateBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branchName: String, targetCommit: Commit?) =
        jgit.provide(repositoryPath) { git ->
            // Fork-only: JGit would write the files that a sparse checkout leaves out, and take them out of it
            if (sparseCheckout.applies(git.repository)) {
                // Refused as JGit's checkout refuses, which the dialog words
                val repository = git.repository
                if (!Repository.isValidRefName(Constants.R_HEADS + branchName)) {
                    raiseError(CreateBranchError.NameNotAllowed(branchName))
                }
                if (repository.exactRef(Constants.R_HEADS + branchName) != null) {
                    raiseError(CreateBranchError.BranchAlreadyExists(branchName))
                }

                val arguments = listOfNotNull("switch", "--create", branchName, targetCommit?.hash)
                sparseCheckout.run(repository, *arguments.toTypedArray()).bind()
                return@provide
            }

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