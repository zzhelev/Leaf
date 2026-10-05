package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ICheckHasUncommittedChangesGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<Boolean, GitError>
}