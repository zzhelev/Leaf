package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.interfaces.IPersistCommitMessageGitAction
import javax.inject.Inject

class PersistCommitMessageUseCase @Inject constructor(
    private val persistCommitMessageGitAction: IPersistCommitMessageGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(message: String?) {
        val messageToPersist = message?.ifBlank { null }
        useCaseExecutor.execute { repositoryPath ->
            val result = persistCommitMessageGitAction(repositoryPath, messageToPersist)
                .onErr {
                    printError(TAG, "Failed to persist commit message: $it")
                }

            result
        }
    }
}
