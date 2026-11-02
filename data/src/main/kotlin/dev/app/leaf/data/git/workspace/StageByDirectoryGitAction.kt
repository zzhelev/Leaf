package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IStageByDirectoryGitAction
import javax.inject.Inject

class StageByDirectoryGitAction @Inject constructor(
    private val jgit: JGit,
    private val sparseCheckout: SparseCheckoutGit,
) : IStageByDirectoryGitAction {
    override suspend operator fun invoke(repositoryPath: String, dir: String) = jgit.provide(repositoryPath) { git ->
        // Fork-only: JGit stages the deletion of the files under a folder that a sparse checkout partly skips, as they
        // aren't on disk, and forgets that the index skips them
        if (sparseCheckout.applies(git.repository)) {
            sparseCheckout.run(git.repository, "--literal-pathspecs", "add", "--", dir).bind()
            return@provide
        }

        git
            .add()
            .addFilepattern(dir)
            .call()

        Unit
    }
}
