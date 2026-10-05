package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

interface ISetTrackingBranchGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        branch: Branch,
        remoteName: String?,
        remoteBranch: Branch?
    ): Either<Unit, GitError>

    suspend operator fun invoke(
        repositoryPath: String,
        refName: String,
        remoteName: String?,
        remoteBranchName: String?
    ): Either<Unit, GitError>
}