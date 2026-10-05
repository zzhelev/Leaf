package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ICheckoutCommitGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

class CheckoutCommitGitAction @Inject constructor(
    private val jgit: JGit,
) : ICheckoutCommitGitAction {
    override suspend operator fun invoke(repositoryPath: String, commit: Commit) =
        this@CheckoutCommitGitAction(repositoryPath, commit.hash)

    override suspend operator fun invoke(repositoryPath: String, hash: String) = jgit.provide(repositoryPath) { git ->
        git
            .checkout()
            .setName(hash)
            .call()

        Unit
    }
}
