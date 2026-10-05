package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IStartRebaseInteractiveGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class StartRebaseInteractiveUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val startRebaseInteractiveGitAction: IStartRebaseInteractiveGitAction,
) {
    operator fun invoke(commit: Commit) =  useCaseExecutor.executeLaunch(
        taskType = TaskType.RebaseInteractive,
        dataToRefresh = arrayOf(DataToRefresh.REPO_STATE),
    ) { repositoryPath ->
        startRebaseInteractiveGitAction(repositoryPath, commit)
    }
}