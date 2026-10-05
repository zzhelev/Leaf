package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IStageEntryGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

private const val TAG = "StatusStageUseCase"

class StatusStageUseCase @Inject constructor(
    private val stageEntryGitAction: IStageEntryGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(statusEntry: StatusEntry) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.StageFile,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
        ) { repositoryPath ->
            stageEntryGitAction(repositoryPath, statusEntry)
        }
    }
}
