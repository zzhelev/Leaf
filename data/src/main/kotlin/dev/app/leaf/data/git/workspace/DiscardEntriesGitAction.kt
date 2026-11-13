package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.conflicts.conflictedPaths
import dev.app.leaf.data.git.discards.DiscardStore
import dev.app.leaf.data.git.discards.keepingDiscarded
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDiscardEntriesGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

class DiscardEntriesGitAction @Inject constructor(
    private val jgit: JGit,
    // Fork-only: keeps what the discard throws away, for Undo (upstream issue 39)
    private val discardStore: DiscardStore,
) : IDiscardEntriesGitAction {
    override suspend operator fun invoke(repositoryPath: String, statusEntries: List<StatusEntry>, staged: Boolean) =
        jgit.provide(repositoryPath) { git ->
            // Fork-only: a staged rename stands for its old path and its new one
            val paths = statusEntries.flatMap { it.paths }.distinct()
            val movedFiles = statusEntries.filter { it.renamedFrom != null }.map { it.filePath }

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

            // Fork-only: an unstaged discard leaves untracked files alone, so they aren't copied
            val kept = if (staged) {
                paths
            } else {
                statusEntries.filter { it.statusType != StatusType.ADDED }.flatMap { it.paths }.distinct()
            }
            val kind = if (staged) DiscardKind.STAGED_CHANGES else DiscardKind.CHANGES

            keepingDiscarded(discardStore, repositoryPath, git.repository, kind, kept, withIndex = staged) {
                if (staged) {
                    resetPaths(git, paths)
                }

                // Fork-only: JGit can't check out a submodule whose commit moved ("Missing unknown ..."), which failed
                // the whole discard. git's checkout leaves submodules alone too.
                val index = git.repository.readDirCache()
                val workTree = git.repository.workTree
                val files = paths.filter { path ->
                    val entry = index.getEntry(path)

                    // Fork-only: a file added with `git add -N` is new, and its index entry has the empty blob, which
                    // the checkout would write over it, as git restore does. Leaf leaves new files alone (see
                    // IntentToAdd).
                    val isIntentToAddFile = entry?.isIntentToAdd == true &&
                        Files.exists(File(workTree, path).toPath(), LinkOption.NOFOLLOW_LINKS)

                    path !in movedFiles && entry?.fileMode != FileMode.GITLINK && !isIntentToAddFile
                }

                if (files.isNotEmpty()) {
                    checkoutPaths(git, files)
                }

                // Fork-only: undoing a move (`docs/fork/status-renames.md`, D3). The old file is back from HEAD, and
                // the new one, which the reset left untracked, goes, as `git restore --source=HEAD --staged --worktree`
                // does
                if (staged && movedFiles.isNotEmpty()) {
                    deleteMovedFiles(git, movedFiles)
                }
            }
        }

    private suspend fun EitherContext<GitError>.deleteMovedFiles(git: Git, movedFiles: List<String>) =
        withContext(Dispatchers.IO) {
            val workTree = git.repository.workTree.toPath().toRealPath()

            for (path in movedFiles) {
                try {
                    deleteNewFile(workTree, path)
                } catch (e: IOException) {
                    raiseError(GenericError("Deleting $path failed", e))
                }
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