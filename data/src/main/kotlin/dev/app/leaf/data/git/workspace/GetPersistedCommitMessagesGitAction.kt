package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetPersistedCommitMessagesGitAction
import dev.app.leaf.domain.models.PersistedCommitMessage
import javax.inject.Inject

class GetPersistedCommitMessagesGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetPersistedCommitMessagesGitAction {
    override suspend fun invoke(repositoryPath: String): Either<PersistedCommitMessage, AppError> {
        return jgit.provide(repositoryPath) { git ->
            val commitMessage = git.repository.readCommitEditMsg()
            val mergeMessage = git.repository.readMergeCommitMsg()
            val squashMessage = git.repository.readSquashCommitMsg()

            PersistedCommitMessage(commitMessage, mergeMessage, squashMessage)
        }
    }
}