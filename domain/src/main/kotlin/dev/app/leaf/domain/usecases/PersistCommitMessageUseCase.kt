package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.interfaces.IPersistCommitMessageGitAction
import javax.inject.Inject

class PersistCommitMessageUseCase @Inject constructor(
    private val persistCommitMessageGitAction: IPersistCommitMessageGitAction,
) {
    /**
     * Saves [message] in the git dir [repositoryPath]. Fork-only: the caller gives the git dir of the worktree that the
     * message was written in, as a message is saved a second after typing, when the tab may show another worktree.
     */
    suspend operator fun invoke(repositoryPath: String, message: String?) {
        val messageToPersist = message?.ifBlank { null }

        persistCommitMessageGitAction(repositoryPath, messageToPersist)
            .onErr {
                printError(TAG, "Failed to persist commit message: $it")
            }
    }
}
