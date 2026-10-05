package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.extensions.hasUntrackedChanges
import dev.app.leaf.domain.interfaces.ICheckHasUncommittedChangesGitAction
import javax.inject.Inject

class CheckHasUncommittedChangesGitAction @Inject constructor(
    private val jgit: JGit,
) : ICheckHasUncommittedChangesGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<Boolean, GitError> = jgit.provide(repositoryPath) { git ->
        val status = git
            .status()
            .call()

        status.hasUncommittedChanges() || status.hasUntrackedChanges()
    }
}