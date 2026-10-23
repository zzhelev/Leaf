package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IFetchAllRemotesGitAction
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class FetchAllBranchUseCase @Inject constructor(
    private val fetchAllGitAction: IFetchAllRemotesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(specificRemote: Remote? = null) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.Fetch,
            refreshEvenIfFailed = true,
            // Fork-only: a fetch moves remote branches and brings tags, which the log's labels and the side panel show
            dataToRefresh = arrayOf(DataToRefresh.REMOTES, DataToRefresh.TAGS, DataToRefresh.LOG),
        ) { repositoryPath ->
            fetchAllGitAction(repositoryPath, specificRemote)
        }
    }
}