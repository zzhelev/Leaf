package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.exceptions.InvalidRemoteUrlException
import dev.app.leaf.domain.interfaces.IAddRemoteGitAction
import dev.app.leaf.domain.interfaces.IUpdateRemoteGitAction
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.models.TaskType
import org.eclipse.jgit.api.RemoteSetUrlCommand
import javax.inject.Inject

class AddRemoteUseCase @Inject constructor(
    private val addRemoteGitAction: IAddRemoteGitAction,
    private val updateRemoteGitAction: IUpdateRemoteGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(remote: Remote) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.AddRemote,
            dataToRefresh = arrayOf(DataToRefresh.REMOTES),
        ) { repositoryPath ->
            if (remote.fetchUri.isBlank()) {
                throw InvalidRemoteUrlException("Invalid empty fetch URI")
            }

            if (remote.pushUri.isBlank()) {
                throw InvalidRemoteUrlException("Invalid empty push URI")
            }

            addRemoteGitAction(
                repositoryPath = repositoryPath,
                remoteName = remote.name,
                fetchUri = remote.fetchUri
            ).bind()

            updateRemoteGitAction(
                repositoryPath = repositoryPath,
                remoteName = remote.name,
                uri = remote.fetchUri,
                uriType = RemoteSetUrlCommand.UriType.FETCH
            ).bind()

            updateRemoteGitAction(
                repositoryPath = repositoryPath,
                remoteName = remote.name,
                uri = remote.pushUri,
                uriType = RemoteSetUrlCommand.UriType.PUSH
            )
        }
    }
}