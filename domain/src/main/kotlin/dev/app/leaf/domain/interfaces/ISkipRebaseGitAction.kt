package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ISkipRebaseGitAction {
    /** Skips the rebase's commit, and returns whether it stopped at conflicts again, at a later commit (fork-only). */
    suspend operator fun invoke(repositoryPath: String): Either<Boolean, GitError>
}