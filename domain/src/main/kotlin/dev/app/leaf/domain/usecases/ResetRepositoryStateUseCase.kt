package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IAbortRebaseGitAction
import dev.app.leaf.domain.interfaces.IResetRepositoryStateGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class ResetRepositoryStateUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val resetRepositoryStateGitAction: IResetRepositoryStateGitAction,
) {
    operator fun invoke() {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ResetRepoState,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            resetRepositoryStateGitAction(repositoryPath)
        }
    }
}
