package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.StatusEntry
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IUnstageEntryGitAction {
    suspend operator fun invoke(repositoryPath: String, statusEntry: StatusEntry): Either<Unit, AppError>
}