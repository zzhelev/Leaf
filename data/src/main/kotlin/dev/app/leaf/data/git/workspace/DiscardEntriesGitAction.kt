package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.conflicts.conflictedPaths
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDiscardEntriesGitAction
import dev.app.leaf.domain.models.StatusEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import javax.inject.Inject

class DiscardEntriesGitAction @Inject constructor(
    private val jgit: JGit,
) : IDiscardEntriesGitAction {
    override suspend operator fun invoke(repositoryPath: String, statusEntries: List<StatusEntry>, staged: Boolean) =
        jgit.provide(repositoryPath) { git ->
            val paths = statusEntries.map { it.filePath }

            // Fork-only: without paths, JGit's reset would reset the whole index, and its checkout fails
            if (paths.isEmpty()) {
                return@provide
            }

            // Fork-only: resetting a file with conflicts would take HEAD's version without saying which side that is.
            // The index tells, as the status may be stale.
            val conflicted = conflictedPaths(git, paths)

            if (conflicted.isNotEmpty()) {
                raiseError(ConflictError.CantDiscard(conflicted))
            }

            if (staged) {
                resetPaths(git, paths)
            }

            checkoutPaths(git, paths)
        }

    private suspend fun resetPaths(git: Git, paths: List<String>) =
        withContext(Dispatchers.IO) {
            git
                .reset()
                .apply {
                    for (path in paths) {
                        addPath(path)
                    }
                }
                .call()
        }

    private fun checkoutPaths(git: Git, paths: List<String>) {
        git
            .checkout()
            .apply {
                for (path in paths) {
                    addPath(path)
                }
            }
            .call()
    }
}