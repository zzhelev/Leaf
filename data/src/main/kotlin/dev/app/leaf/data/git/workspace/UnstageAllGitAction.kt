package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IUnstageAllGitAction
import dev.app.leaf.domain.models.StatusEntry
import javax.inject.Inject

class UnstageAllGitAction @Inject constructor(
    private val jgit: JGit,
    private val sparseCheckout: SparseCheckoutGit,
) : IUnstageAllGitAction {
    override suspend operator fun invoke(repositoryPath: String, entries: List<StatusEntry>?) =
        jgit.provide(repositoryPath) { git ->
            // Fork-only: JGit's reset of the whole index, which no files or an empty list ask for, forgets that it skips
            // the files that a sparse checkout leaves out, and they show as deleted. Resetting files by path, as the
            // search does, leaves the others alone.
            if (entries.isNullOrEmpty() && sparseCheckout.applies(git.repository)) {
                sparseCheckout.run(git.repository, "reset").bind()
                return@provide
            }

            git
                .reset()
                .apply {
                    // Fork-only: both paths of a rename
                    entries?.forEach { entry ->
                        entry.paths.forEach(::addPath)
                    }
                }
                .call()

            Unit
        }
}