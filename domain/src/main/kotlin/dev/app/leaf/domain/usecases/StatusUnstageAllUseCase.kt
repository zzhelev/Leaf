package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IUnstageAllGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class StatusUnstageAllUseCase @Inject constructor(
    private val unstageAllGitAction: IUnstageAllGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(statusEntries: List<StatusEntry>?) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.UnstageAllFiles,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
        ) { repositoryPath ->
            unstageAllGitAction(repositoryPath, statusEntries)
        }
    }
}
