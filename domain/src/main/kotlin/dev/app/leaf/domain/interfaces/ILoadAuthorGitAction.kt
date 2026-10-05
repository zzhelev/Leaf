package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.AuthorInfo
import org.eclipse.jgit.api.Git

interface ILoadAuthorGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<AuthorInfo, GitError>
}