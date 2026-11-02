package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IResetToCommitGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.usecases.ResetType
import org.eclipse.jgit.api.ResetCommand
import javax.inject.Inject

class ResetToCommitGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
    private val sparseCheckout: SparseCheckoutGit,
) : IResetToCommitGitAction {
    override suspend operator fun invoke(repositoryPath: String, commit: Commit, resetType: ResetType) =
        jgit.provide(repositoryPath) { git ->
            val reset = when (resetType) {
                ResetType.SOFT -> ResetCommand.ResetType.SOFT
                ResetType.MIXED -> ResetCommand.ResetType.MIXED
                ResetType.HARD -> ResetCommand.ResetType.HARD
            }
            // Fork-only: JGit can't download what a partial clone lacks, and would stop halfway
            if (resetType == ResetType.HARD) {
                partialClone.prefetchCheckout(git.repository, commit.hash).bind()
            }

            // Fork-only: JGit's hard reset writes the files that a sparse checkout leaves out, and its mixed reset
            // forgets that the index skips them. A soft reset leaves the index alone.
            if (resetType != ResetType.SOFT && sparseCheckout.applies(git.repository)) {
                val mode = if (resetType == ResetType.HARD) "--hard" else "--mixed"

                sparseCheckout.run(git.repository, "reset", mode, commit.hash).bind()
                return@provide
            }

            git
                .reset()
                .setMode(reset)
                .setRef(commit.hash)
                .call()

            Unit
        }
}
