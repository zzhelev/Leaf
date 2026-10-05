package dev.app.leaf.data.git.remotes

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IUpdateRemoteGitAction
import org.eclipse.jgit.api.RemoteSetUrlCommand
import org.eclipse.jgit.transport.URIish
import javax.inject.Inject

class UpdateRemoteGitAction @Inject constructor(
    private val jgit: JGit,
) : IUpdateRemoteGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        remoteName: String,
        uri: String,
        uriType: RemoteSetUrlCommand.UriType
    ) = jgit.provide(repositoryPath) { git ->
        git
            .remoteSetUrl()
            .setRemoteName(remoteName)
            .setRemoteUri(URIish(uri))
            .setUriType(uriType)
            .call()
    }
}