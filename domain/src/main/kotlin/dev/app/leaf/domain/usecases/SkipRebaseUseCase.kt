package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IAbortRebaseGitAction
import dev.app.leaf.domain.interfaces.ISkipRebaseGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class SkipRebaseUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val skipRebaseGitAction: ISkipRebaseGitAction,
) {
    operator fun invoke() {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.SkipRebase,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            skipRebaseGitAction(repositoryPath)
        }
    }
}