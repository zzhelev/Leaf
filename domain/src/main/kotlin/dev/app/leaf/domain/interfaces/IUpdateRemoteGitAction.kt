package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import org.eclipse.jgit.api.RemoteSetUrlCommand
import org.eclipse.jgit.transport.RemoteConfig

interface IUpdateRemoteGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        remoteName: String,
        uri: String,
        uriType: RemoteSetUrlCommand.UriType
    ): Either<RemoteConfig?, GitError>
}