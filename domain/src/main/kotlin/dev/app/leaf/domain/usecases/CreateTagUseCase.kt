package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ICreateTagGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class CreateTagUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val createTagGitAction: ICreateTagGitAction,
) {
    operator fun invoke(tag: String, revCommit: Commit) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.CreateTag,
            dataToRefresh = arrayOf(DataToRefresh.LOG, DataToRefresh.TAGS),
        ) { repositoryPath ->
            createTagGitAction(repositoryPath, tag, revCommit)
        }
    }
}