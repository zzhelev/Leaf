package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IAbortRebaseGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class AbortRebaseUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val abortRebaseGitAction: IAbortRebaseGitAction,
) {
    operator fun invoke() {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.AbortRebase,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            abortRebaseGitAction(repositoryPath)
        }
    }
}