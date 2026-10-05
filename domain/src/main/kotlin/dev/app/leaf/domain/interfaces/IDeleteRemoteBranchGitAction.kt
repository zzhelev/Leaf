package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

interface IDeleteRemoteBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, ref: Branch): Either<Unit, GitError>
}