package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IUnstageByDirectoryGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class UnstageByDirectoryUseCase @Inject constructor(
    private val unstageByDirectoryGitAction: IUnstageByDirectoryGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(dir: String) = useCaseExecutor.executeLaunch(
        taskType = TaskType.StageDir,
        dataToRefresh = arrayOf(DataToRefresh.STATUS),
    ) { repositoryPath ->
        unstageByDirectoryGitAction(repositoryPath, dir)
    }
}