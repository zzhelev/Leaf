package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IStageByDirectoryGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class StageByDirectoryUseCase @Inject constructor(
    private val stageByDirectoryGitAction: IStageByDirectoryGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(dir: String) = useCaseExecutor.executeLaunch(
        taskType = TaskType.StageDir,
        dataToRefresh = arrayOf(DataToRefresh.STATUS),
    ) { repositoryPath ->
        stageByDirectoryGitAction(repositoryPath, dir)
    }
}