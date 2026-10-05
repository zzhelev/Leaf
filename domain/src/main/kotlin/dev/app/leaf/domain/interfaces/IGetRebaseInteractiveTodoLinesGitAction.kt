package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.RebaseLine

interface IGetRebaseInteractiveTodoLinesGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<List<RebaseLine>, GitError>
}