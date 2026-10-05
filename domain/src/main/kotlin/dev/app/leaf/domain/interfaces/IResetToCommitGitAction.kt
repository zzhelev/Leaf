package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.usecases.ResetType
import org.eclipse.jgit.api.Git

interface IResetToCommitGitAction {
    suspend operator fun invoke(repositoryPath: String, commit: Commit, resetType: ResetType): Either<Unit, GitError>
}