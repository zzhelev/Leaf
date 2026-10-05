package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IGetTrackingBranchGitAction
import dev.app.leaf.domain.interfaces.ISetTrackingBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class SetTrackingBranchUseCase @Inject constructor(
    private val setTrackingBranchGitAction: ISetTrackingBranchGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(branch: Branch, remoteName: String?, remoteBranch: Branch?) = useCaseExecutor.executeLaunchAsync(
        taskType = TaskType.ChangeBranchUpstream,
        dataToRefresh = arrayOf(DataToRefresh.LOG, DataToRefresh.BRANCHES),
    ) {repositoryPath ->
        setTrackingBranchGitAction(repositoryPath, branch, remoteName, remoteBranch)
    }
}