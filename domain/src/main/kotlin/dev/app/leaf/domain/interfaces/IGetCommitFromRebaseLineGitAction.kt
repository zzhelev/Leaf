package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit

interface IGetCommitFromRebaseLineGitAction {
    suspend operator fun invoke(repositoryPath: String, commitHash: String, shortMessage: String): Either<Commit?, GitError>
}