package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IDeleteRemoteGitAction {
    suspend operator fun invoke(repositoryPath: String, remoteName: String): Either<Unit, GitError>
}