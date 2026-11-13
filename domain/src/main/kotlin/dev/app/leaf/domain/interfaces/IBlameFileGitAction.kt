package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import org.eclipse.jgit.blame.BlameResult

interface IBlameFileGitAction {
    /** Fork-only: [movedFrom] is where a staged rename's [filePath] was, whose history its lines have. */
    suspend operator fun invoke(
        repositoryPath: String,
        filePath: String,
        movedFrom: String? = null,
    ): Either<BlameResult, GitError>
}