package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.RepositoryState

interface IGetRepositoryStateGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<RepositoryState, GitError>
}