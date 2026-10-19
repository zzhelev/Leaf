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
import org.eclipse.jgit.lib.FileMode
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

            // Fork-only: JGit can't check out a submodule whose commit moved ("Missing unknown ..."), which failed the
            // whole discard. git's checkout leaves submodules alone too.
            val index = git.repository.readDirCache()
            val files = paths.filter { index.getEntry(it)?.fileMode != FileMode.GITLINK }

            if (files.isNotEmpty()) {
                checkoutPaths(git, files)
            }
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