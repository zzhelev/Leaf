package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ICherryPickCommitGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class CherryPickCommitUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val cherryPickGitAction: ICherryPickCommitGitAction,
) {
    operator fun invoke(commit: Commit) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.CherryPickCommit,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG, DataToRefresh.BRANCHES, DataToRefresh.REPO_STATE),
        ) { repositoryPath ->
            cherryPickGitAction(repositoryPath, commit)
        }
    }
}