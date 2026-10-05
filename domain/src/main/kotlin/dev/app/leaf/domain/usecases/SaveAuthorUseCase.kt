package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ISaveAuthorGitAction
import dev.app.leaf.domain.models.AuthorInfo
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class SaveAuthorUseCase @Inject constructor(
    private val saveAuthorGitAction: ISaveAuthorGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(authorInfo: AuthorInfo) {
        // TODO This should be "execute" and the UI should handle the error
        useCaseExecutor.executeLaunch(
            taskType = TaskType.SaveAuthor,
            dataToRefresh = arrayOf(DataToRefresh.GIT_CONFIG),
        ) { repositoryPath ->
            saveAuthorGitAction(repositoryPath, authorInfo)
        }
    }
}