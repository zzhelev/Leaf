package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IAddRemoteGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        remoteName: String,
        fetchUri: String,
    ): Either<Unit, GitError>
}