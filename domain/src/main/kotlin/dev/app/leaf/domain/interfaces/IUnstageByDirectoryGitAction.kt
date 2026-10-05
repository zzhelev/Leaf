package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IUnstageByDirectoryGitAction {
    suspend operator fun invoke(repositoryPath: String, dir: String): Either<Unit, GitError>
}