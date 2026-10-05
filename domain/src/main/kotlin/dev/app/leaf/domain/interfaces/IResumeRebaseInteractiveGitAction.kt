package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import org.eclipse.jgit.api.RebaseCommand

interface IResumeRebaseInteractiveGitAction {
    suspend operator fun invoke(repositoryPath: String, interactiveHandler: RebaseCommand.InteractiveHandler): Either<Unit, GitError>
}