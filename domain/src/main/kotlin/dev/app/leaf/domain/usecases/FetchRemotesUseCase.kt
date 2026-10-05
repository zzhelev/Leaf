package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IFetchAllRemotesGitAction
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class FetchRemotesUseCase @Inject constructor(
    private val fetchAllRemotesGitAction: IFetchAllRemotesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(specificRemote: Remote? = null) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.Fetch,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            fetchAllRemotesGitAction(repositoryPath, specificRemote)
        }
    }
}