package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IUnstageByDirectoryGitAction
import javax.inject.Inject

class UnstageByDirectoryGitAction @Inject constructor(
    private val jgit: JGit,
) : IUnstageByDirectoryGitAction {
    override suspend operator fun invoke(repositoryPath: String, dir: String): Either<Unit, GitError> =
        jgit.provide(repositoryPath) { git ->
            git
                .reset()
                .addPath(dir)
                .call()
        }
}
