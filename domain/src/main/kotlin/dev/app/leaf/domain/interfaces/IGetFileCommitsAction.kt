package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.api.Git

interface IGetFileCommitsAction {
    suspend operator fun invoke(
        repositoryPath: String,
        filePath: String
    ): Either<List<Commit>, GitError>
}