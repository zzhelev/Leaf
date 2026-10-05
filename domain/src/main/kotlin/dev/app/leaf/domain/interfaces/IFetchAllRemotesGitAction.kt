package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Remote

interface IFetchAllRemotesGitAction {
    suspend operator fun invoke(repositoryPath: String, specificRemote: Remote? = null): Either<Unit, GitError>
}