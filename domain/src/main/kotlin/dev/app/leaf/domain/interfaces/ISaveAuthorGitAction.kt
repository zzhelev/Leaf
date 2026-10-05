package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.AuthorInfo
import org.eclipse.jgit.api.Git

interface ISaveAuthorGitAction {
    suspend operator fun invoke(repositoryPath: String, newAuthorInfo: AuthorInfo): Either<Unit, GitError>
}