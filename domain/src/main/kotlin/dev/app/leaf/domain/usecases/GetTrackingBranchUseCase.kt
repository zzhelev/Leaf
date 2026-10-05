package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IGetTrackingBranchGitAction
import dev.app.leaf.domain.models.Branch
import javax.inject.Inject

class GetTrackingBranchUseCase @Inject constructor(
    private val getTrackingBranchGitAction: IGetTrackingBranchGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(branch: Branch) = useCaseExecutor.execute { repositoryPath ->
        getTrackingBranchGitAction(repositoryPath, branch)
    }
}