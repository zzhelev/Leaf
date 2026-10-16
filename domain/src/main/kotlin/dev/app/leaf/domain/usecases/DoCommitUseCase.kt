package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.SignOffConstants
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.errors.onOk
import dev.app.leaf.domain.interfaces.ICleanUpAfterCommitGitAction
import dev.app.leaf.domain.interfaces.IDoCommitGitAction
import dev.app.leaf.domain.interfaces.ILoadSignOffConfigGitAction
import dev.app.leaf.domain.interfaces.IPutBackMergeAutostashGitAction
import dev.app.leaf.domain.models.AutostashPutBackAfter
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
    private val cleanUpAfterCommitGitAction: ICleanUpAfterCommitGitAction,
    private val putBackMergeAutostashGitAction: IPutBackMergeAutostashGitAction,
) {
    operator fun invoke(
        message: String,
        amend: Boolean,
        author: Identity?,
    ): Deferred<Either<Commit, AppError>> {
        return useCaseExecutor.executeLaunchAsync(
            taskType = TaskType.DoCommit,
            // Fork-only: STASHES, as the autostash that the commit puts back may go to the stash list
            dataToRefresh = arrayOf(
                DataToRefresh.STATUS,
                DataToRefresh.BRANCHES,
                DataToRefresh.LOG,
                DataToRefresh.REPO_STATE,
                DataToRefresh.STASHES,
            ),
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
                persistCommitMessageUseCase(repositoryPath, null)

                // Fork-only: removes SQUASH_MSG and the merge's files that git's commit removes and JGit's doesn't
                cleanUpAfterCommitGitAction(repositoryPath).onErr {
                    printError(TAG, "Failed to clean up after the commit: $it")
                }

                // Fork-only: puts back what merge.autoStash set aside, as git's commit does after any commit
                putBackMergeAutostashGitAction(repositoryPath, AutostashPutBackAfter.COMMIT).onErr {
                    printError(TAG, "Failed to put back the merge's autostash: $it")
                }
            }
    }
}
