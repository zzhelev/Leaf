package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDeleteTagGitAction
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class DeleteTagUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val deleteTagGitAction: IDeleteTagGitAction,
) {
    operator fun invoke(tag: Tag) = useCaseExecutor.executeLaunch(
        taskType = TaskType.DeleteTag,
        dataToRefresh = arrayOf(DataToRefresh.TAGS, DataToRefresh.LOG),
    ) { repositoryPath ->
        deleteTagGitAction(repositoryPath, tag)
    }
}