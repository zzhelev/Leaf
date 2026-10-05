package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import org.eclipse.jgit.blame.BlameResult

interface IBlameFileGitAction {
    suspend operator fun invoke(repositoryPath: String, filePath: String): Either<BlameResult, GitError>
}