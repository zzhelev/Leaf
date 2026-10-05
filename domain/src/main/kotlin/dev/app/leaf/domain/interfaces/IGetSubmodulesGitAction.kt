package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Submodule

interface IGetSubmodulesGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<Map<String, Submodule>, GitError>
}