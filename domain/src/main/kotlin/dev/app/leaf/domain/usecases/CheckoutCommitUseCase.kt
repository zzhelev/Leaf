package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ICheckoutCommitGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class CheckoutCommitUseCase @Inject constructor(
    private val checkoutCommitGitAction: ICheckoutCommitGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(commit: Commit) {
        invoke(commit.hash)
    }

    operator fun invoke(hash: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.CheckoutCommit,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG, DataToRefresh.BRANCHES),
        ) { repositoryPath ->
            checkoutCommitGitAction(repositoryPath, hash)
        }
    }
}