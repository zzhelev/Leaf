package dev.app.leaf.data.git.remotes

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IDeleteRemoteGitAction
import javax.inject.Inject

class DeleteRemoteGitAction @Inject constructor(
    private val jgit: JGit,
) : IDeleteRemoteGitAction {
    override suspend operator fun invoke(repositoryPath: String, remoteName: String): Either<Unit, GitError> {
        return jgit.provide(repositoryPath) { git ->
            git
                .remoteRemove()
                .setRemoteName(remoteName)
                .call()
        }
    }
}