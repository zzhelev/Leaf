package dev.app.leaf.data.git.remotes

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.domain.interfaces.IGetRemotesGitAction
import javax.inject.Inject

class GetRemotesGitAction @Inject constructor(
    private val remoteMapper: RemoteConfigToRemoteMapper,
    private val jgit: JGit,
) : IGetRemotesGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        git
            .remoteList()
            .call()
            .map { remoteConfig -> remoteMapper.toDomain(remoteConfig) }
    }
}