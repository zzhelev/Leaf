package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IRebaseBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class RebaseBranchUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val rebaseBranchGitAction: IRebaseBranchGitAction,
) {
    operator fun invoke(branch: Branch) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.RebaseBranch,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            stoppedAtConflicts = { hasStopped -> hasStopped },
        ) { repositoryPath ->
            rebaseBranchGitAction(repositoryPath, branch)
        }
    }
}