package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Status

interface IGetStatusGitAction {
    suspend operator fun invoke(repository: String, paths: List<String> = emptyList()): Either<Status, AppError>
}