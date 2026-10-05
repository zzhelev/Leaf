package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit

interface IDeleteStashGitAction {
    suspend operator fun invoke(repositoryPath: String, stashInfo: Commit): Either<Unit, GitError>
}