package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetRemoteBranchesGitAction
import dev.app.leaf.domain.interfaces.IGetRemotesGitAction
import dev.app.leaf.domain.models.RemoteInfo
import javax.inject.Inject

class GetRemotesUseCase @Inject constructor(
    private val getRemotesGitAction: IGetRemotesGitAction,
    private val getRemoteBranchesGitAction: IGetRemoteBranchesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke() = either {
        useCaseExecutor.execute { repositoryPath ->
            getRemoteInfoList(repositoryPath)
        }
    }
    private suspend fun getRemoteInfoList(repositoryPath: String) = either<List<RemoteInfo>, GitError> {
        val allRemoteBranches = getRemoteBranchesGitAction(repositoryPath).bind()
        val remoteInfoList = getRemotesGitAction(repositoryPath).bind()

        val remotes = remoteInfoList.map { remote ->
            val remoteBranches = allRemoteBranches.filter { branch ->
                branch.name.startsWith("refs/remotes/${remote.name}")
            }
            RemoteInfo(remote, remoteBranches)
        }

        Either.Ok(remotes)
    }
}