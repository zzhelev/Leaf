package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.repository.headCommitMessage
import dev.app.leaf.data.git.repository.readCommitDraft
import dev.app.leaf.data.git.repository.readRebaseEditMessage
import dev.app.leaf.data.git.repository.rebaseEditStop
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
            // Fork-only: Leaf's own file, not git's COMMIT_EDITMSG
            val commitMessage = git.repository.readCommitDraft()
            // Fork-only: without the conflicts that git lists as comments, which JGit's commit would keep
            val mergeMessage = git.repository.readMergeCommitMsg()?.let(::withoutConflictsHint)
            val squashMessage = git.repository.readSquashCommitMsg()
            // Fork-only: the message for the commit that a rebase stopped at to edit: the one saved for it, or else
            // the commit's own, which `git commit --amend` starts from
            val rebaseEditStop = git.repository.rebaseEditStop()
            val rebaseEditMessage = rebaseEditStop?.let {
                git.repository.readRebaseEditMessage(it) ?: git.repository.headCommitMessage()
            }

            PersistedCommitMessage(commitMessage, mergeMessage, squashMessage, rebaseEditStop, rebaseEditMessage)
        }
    }
}