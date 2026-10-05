package dev.app.leaf.data.git.remotes

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IAddRemoteGitAction
import org.eclipse.jgit.transport.URIish
import javax.inject.Inject

class AddRemoteGitAction @Inject constructor(
    private val jgit: JGit,
) : IAddRemoteGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        remoteName: String,
        fetchUri: String,
    ) = jgit.provide(repositoryPath) { git ->
        git
            .remoteAdd()
            .setName(remoteName)
            .setUri(URIish(fetchUri))
            .call()

        Unit
    }
}