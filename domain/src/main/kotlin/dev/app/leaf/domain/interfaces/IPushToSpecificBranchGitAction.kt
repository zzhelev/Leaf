package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

interface IPushToSpecificBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, force: Boolean, pushTags: Boolean, remoteBranch: Branch): Either<Unit, GitError>
}