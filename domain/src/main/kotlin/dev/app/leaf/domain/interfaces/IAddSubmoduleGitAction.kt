package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IAddSubmoduleGitAction {
    suspend operator fun invoke(repositoryPath: String, name: String, path: String, uri: String): Either<Unit, GitError>
}