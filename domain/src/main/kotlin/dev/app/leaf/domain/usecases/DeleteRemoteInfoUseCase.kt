package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDeleteLocallyRemoteBranchesGitAction
import dev.app.leaf.domain.interfaces.IDeleteRemoteGitAction
import dev.app.leaf.domain.models.RemoteInfo
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class DeleteRemoteInfoUseCase @Inject constructor(
    private val deleteRemoteGitAction: IDeleteRemoteGitAction,
    private val deleteLocallyRemoteBranchesGitAction: IDeleteLocallyRemoteBranchesGitAction,
    private val useCaseExecutor: UseCaseExecutor,

    ) {
    operator fun invoke(remoteInfo: RemoteInfo) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.DeleteRemote,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            deleteRemoteGitAction(repositoryPath, remoteInfo.remote.name)

            val remoteBranchesToDelete = remoteInfo.branchesList

            deleteLocallyRemoteBranchesGitAction(repositoryPath, remoteBranchesToDelete.map { it.name })
        }
    }
}
