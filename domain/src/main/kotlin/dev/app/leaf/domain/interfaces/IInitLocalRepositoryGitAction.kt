package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import java.io.File

interface IInitLocalRepositoryGitAction {
    suspend operator fun invoke(repoDir: File): Either<Unit, GitError>
}
