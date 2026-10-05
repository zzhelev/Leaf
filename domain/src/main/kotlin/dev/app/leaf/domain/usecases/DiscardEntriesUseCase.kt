package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDiscardEntriesGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class DiscardEntriesUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val discardEntriesGitAction: IDiscardEntriesGitAction,
) {
    operator fun invoke(statusEntries: List<StatusEntry>, isStaged: Boolean) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.DiscardFile,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG),
        ) { repositoryPath ->
            discardEntriesGitAction(repositoryPath, statusEntries, isStaged)
        }
    }
}
