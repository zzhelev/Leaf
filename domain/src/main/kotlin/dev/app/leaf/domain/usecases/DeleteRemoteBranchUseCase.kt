package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDeleteBranchGitAction
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class DeleteRemoteBranchUseCase @Inject constructor(
    private val deleteRemoteBranchGitAction: IDeleteRemoteBranchGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(branch: Branch) {
        useCaseExecutor.executeLaunch(
            TaskType.DeleteBranch,
            dataToRefresh = arrayOf(DataToRefresh.ALL), // TODO Refresh only log?
        ) { repositoryPath ->
            deleteRemoteBranchGitAction(repositoryPath, branch)
        }
    }
}