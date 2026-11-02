package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IUnstageByDirectoryGitAction
import javax.inject.Inject

class UnstageByDirectoryGitAction @Inject constructor(
    private val jgit: JGit,
    private val sparseCheckout: SparseCheckoutGit,
) : IUnstageByDirectoryGitAction {
    override suspend operator fun invoke(repositoryPath: String, dir: String): Either<Unit, GitError> =
        jgit.provide(repositoryPath) { git ->
            // Fork-only: JGit resets the files under a folder that a sparse checkout partly skips as if they were
            // deleted, and forgets that the index skips them
            if (sparseCheckout.applies(git.repository)) {
                sparseCheckout.run(git.repository, "--literal-pathspecs", "reset", "--", dir).bind()
                return@provide
            }

            git
                .reset()
                .addPath(dir)
                .call()
        }
}
