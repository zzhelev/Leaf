package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.api.Git

interface ICreateTagGitAction {
    suspend operator fun invoke(repositoryPath: String, tag: String, commit: Commit): Either<Unit, GitError>
}