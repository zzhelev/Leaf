package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IGetStashListGitAction
import dev.app.leaf.domain.interfaces.IPopStashGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class PopStashUseCase @Inject constructor(
    private val popStashGitAction: IPopStashGitAction,
    private val getStashListGitAction: IGetStashListGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(commit: Commit?) = useCaseExecutor.executeLaunch(
        taskType = TaskType.PopStash,
        refreshEvenIfFailed = true,
        dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG, DataToRefresh.STASHES),
    ) { repositoryPath ->
        val stashCommit = commit ?: getStashListGitAction(repositoryPath).bind().firstOrNull()

        if (stashCommit == null) {
            raiseError(GenericError("No stashes found")) // TODO Refactor this to a proper type
        }

        popStashGitAction(repositoryPath, stashCommit)
    }
}