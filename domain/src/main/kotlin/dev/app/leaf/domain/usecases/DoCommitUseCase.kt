package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.SignOffConstants
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.onOk
import dev.app.leaf.domain.interfaces.IDoCommitGitAction
import dev.app.leaf.domain.interfaces.ILoadSignOffConfigGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Deferred
import javax.inject.Inject

class DoCommitUseCase @Inject constructor(
    private val doCommitGitAction: IDoCommitGitAction,
    private val useCaseExecutor: UseCaseExecutor,
    private val loadSignOffConfigGitAction: ILoadSignOffConfigGitAction,
    private val getAuthorUseCase: GetAuthorUseCase,
    private val persistCommitMessageUseCase: PersistCommitMessageUseCase,
) {
    operator fun invoke(
        message: String,
        amend: Boolean,
        author: Identity?,
    ): Deferred<Either<Commit, AppError>> {
        return useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.DoCommit,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.BRANCHES, DataToRefresh.LOG, DataToRefresh.REPO_STATE),
        ) { repositoryPath ->
            commit(repositoryPath, message, amend, author)
        }
    }

    /**
     * Fork-only: commits within a task that is already running, as continuing a rebase does. [invoke] starts a task of
     * its own: continuing a rebase didn't wait for it, and its end cleared the processing screen while the rebase ran.
     */
    suspend fun commit(
        repositoryPath: String,
        message: String,
        amend: Boolean,
        author: Identity?,
    ): Either<Commit, AppError> = either {
        val signOffConfig = loadSignOffConfigGitAction(repositoryPath).bind()

        val finalMessage = if (signOffConfig.isEnabled) {
            val authorToSign = author ?: getAuthorUseCase().bind().identityToUse()

            val signature = signOffConfig.format
                .replace(SignOffConstants.DEFAULT_SIGN_OFF_FORMAT_USER, authorToSign.name.orEmpty())
                .replace(SignOffConstants.DEFAULT_SIGN_OFF_FORMAT_EMAIL, authorToSign.email.orEmpty())

            "$message\n\n$signature"
        } else
            message


        doCommitGitAction(repositoryPath, finalMessage, amend, author)
            .onOk {
                persistCommitMessageUseCase(null)
            }
    }
}
