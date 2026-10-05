package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.PersistedCommitMessage

interface IGetPersistedCommitMessagesGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<PersistedCommitMessage, AppError>
}