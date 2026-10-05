package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.SignOffConfig

interface ISaveLocalRepositoryConfigGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        signOffConfig: SignOffConfig,
    ): Either<Unit, GitError>
}