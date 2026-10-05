package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.RebaseInteractiveState
import org.eclipse.jgit.api.Git

interface IGetRebaseInteractiveStateGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<RebaseInteractiveState, GitError>
}