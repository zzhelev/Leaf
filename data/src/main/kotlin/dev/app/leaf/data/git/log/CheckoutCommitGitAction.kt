package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.ICheckoutCommitGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

class CheckoutCommitGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
    private val sparseCheckout: SparseCheckoutGit,
) : ICheckoutCommitGitAction {
    override suspend operator fun invoke(repositoryPath: String, commit: Commit) =
        this@CheckoutCommitGitAction(repositoryPath, commit.hash)

    override suspend operator fun invoke(repositoryPath: String, hash: String) = jgit.provide(repositoryPath) { git ->
        // Fork-only: JGit can't download what a partial clone lacks, and would stop halfway
        partialClone.prefetchCheckout(git.repository, hash).bind()

        // Fork-only: JGit would write the files that a sparse checkout leaves out, and take them out of it
        if (sparseCheckout.applies(git.repository)) {
            sparseCheckout.run(git.repository, "switch", "--detach", hash).bind()
            return@provide
        }

        git
            .checkout()
            .setName(hash)
            .call()

        Unit
    }
}
