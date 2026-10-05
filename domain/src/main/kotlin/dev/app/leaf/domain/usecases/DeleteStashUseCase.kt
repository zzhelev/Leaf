package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDeleteStashGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class DeleteStashUseCase @Inject constructor(
    private val deleteStashGitAction: IDeleteStashGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(stash: Commit) = useCaseExecutor.executeLaunch(
        taskType = TaskType.Stash,
        refreshEvenIfFailed = true,
        dataToRefresh = arrayOf(DataToRefresh.STASHES, DataToRefresh.LOG),
    ) { repositoryPath ->
        deleteStashGitAction(repositoryPath, stash)
    }
}