package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import org.eclipse.jgit.lib.Repository
import java.io.File

interface IOpenRepositoryGitAction {
    suspend operator fun invoke(directory: String): Either<String, AppError>
}