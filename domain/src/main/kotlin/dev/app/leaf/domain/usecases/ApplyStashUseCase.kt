package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IApplyStashGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class ApplyStashUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val applyStashGitAction: IApplyStashGitAction,
) {
    operator fun invoke(stashCommit: Commit) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ApplyStash,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            applyStashGitAction(repositoryPath, stashCommit)
        }
    }
}