package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IResetToCommitGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class ResetBranchUseCase @Inject constructor(
    private val resetToCommitGitAction: IResetToCommitGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(revCommit: Commit, resetType: ResetType) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ResetToCommit,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            resetToCommitGitAction(repositoryPath, revCommit, resetType = resetType)
        }
    }
}



enum class ResetType {
    SOFT,
    MIXED,
    HARD,
}