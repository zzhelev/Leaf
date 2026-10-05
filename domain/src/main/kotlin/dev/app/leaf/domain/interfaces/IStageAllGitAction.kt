package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.StatusEntry
import org.eclipse.jgit.api.Git

interface IStageAllGitAction {
    suspend operator fun invoke(repositoryPath: String, entries: List<StatusEntry>?): Either<Unit, AppError>
}