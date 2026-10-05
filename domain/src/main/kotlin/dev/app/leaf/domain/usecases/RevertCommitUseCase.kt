package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IRevertCommitGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class RevertCommitUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val revertCommitGitAction: IRevertCommitGitAction,
) {
    operator fun invoke(commit: Commit) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.RevertCommit,
            refreshEvenIfFailed = true,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG),
        ) { repositoryPath ->
            revertCommitGitAction(repositoryPath, commit)
        }
    }
}