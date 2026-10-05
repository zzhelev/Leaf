package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IStageAllGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

private const val TAG = "StatusStageAllUseCase"

class StatusStageAllUseCase @Inject constructor(
    private val stageAllGitAction: IStageAllGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(entries: List<StatusEntry>?) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.StageAllFiles,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
        ) { repositoryPath ->
            stageAllGitAction(repositoryPath, entries)
        }
    }
}
