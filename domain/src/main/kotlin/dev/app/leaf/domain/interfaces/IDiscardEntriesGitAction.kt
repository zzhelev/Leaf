package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.StatusEntry

interface IDiscardEntriesGitAction {
    suspend operator fun invoke(repositoryPath: String, statusEntries: List<StatusEntry>, staged: Boolean): Either<Unit, GitError>
}