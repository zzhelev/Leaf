package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit

interface ICheckoutCommitGitAction {
    suspend operator fun invoke(repositoryPath: String, commit: Commit): Either<Unit, GitError>
    suspend operator fun invoke(repositoryPath: String, hash: String): Either<Unit, GitError>
}