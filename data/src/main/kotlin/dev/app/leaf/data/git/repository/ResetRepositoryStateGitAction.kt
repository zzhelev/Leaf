package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IResetRepositoryStateGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import javax.inject.Inject

class ResetRepositoryStateGitAction @Inject constructor(
    private val jgit: JGit,
    private val sparseCheckout: SparseCheckoutGit,
) : IResetRepositoryStateGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        git.repository.apply {
            writeMergeCommitMsg(null)
            writeMergeHeads(null)
        }

        // Fork-only: JGit's hard reset writes the files that a sparse checkout leaves out, and takes them out of it
        if (sparseCheckout.applies(git.repository)) {
            sparseCheckout.run(git.repository, "reset", "--hard").bind()
            return@provide
        }

        git.reset()
            .setMode(ResetCommand.ResetType.HARD)
            .call()

        Unit
    }
}