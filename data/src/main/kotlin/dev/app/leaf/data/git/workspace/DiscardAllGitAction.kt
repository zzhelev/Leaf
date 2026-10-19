// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDiscardAllGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.hasConflicts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.inject.Inject

/**
 * "Discard all" of the Unstaged pane. The caller lists the files that its dialog counted. The status is read again
 * here, as an agent may have worked in the worktree while the dialog was open: only an entry that the status still
 * lists as it was is touched, as [StageAllGitAction] does. A file that was staged, committed or conflicted since, and
 * every file that appeared after the dialog opened, is left alone. Nothing here runs `checkout .` or `reset --hard`.
 */
class DiscardAllGitAction @Inject constructor(
    private val getStatusGitAction: GetStatusGitAction,
    private val discardEntriesGitAction: DiscardEntriesGitAction,
    private val jgit: JGit,
) : IDiscardAllGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        restore: List<StatusEntry>,
        newFiles: List<StatusEntry>,
    ) = either<Unit, GitError> {
        val unstaged = getStatusGitAction(repositoryPath).bind().unstaged.toSet()

        val toRestore = restore.filter {
            it in unstaged && it.statusType != StatusType.ADDED && !it.hasConflicts && !it.isSubmodule
        }
        val toDelete = newFiles.filter { it in unstaged && it.statusType == StatusType.ADDED }

        // The restore goes first and refuses files with conflicts: nothing is deleted when it fails
        if (toRestore.isNotEmpty()) {
            discardEntriesGitAction(repositoryPath, toRestore, staged = false).bind()
        }

        if (toDelete.isNotEmpty()) {
            deleteNewFiles(repositoryPath, toDelete).bind()
        }

        Either.Ok(Unit)
    }

    private suspend fun deleteNewFiles(repositoryPath: String, entries: List<StatusEntry>) =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                val workTree = git.repository.workTree.toPath().toRealPath()

                for (entry in entries) {
                    try {
                        deleteNewFile(workTree, entry.filePath)
                    } catch (e: IOException) {
                        raiseError(GenericError("Deleting ${entry.filePath} failed", e))
                    }
                }
            }
        }

    /**
     * Deletes the untracked file at [path], and the folders that it leaves empty, as `git clean -fd` removes them. Never
     * a folder: an untracked entry that is one holds another repository, such as a linked worktree inside this one,
     * and the files of a folder with new files in it are listed one by one. A link is deleted as a link. Anything
     * that isn't inside the working tree is skipped.
     */
    private fun deleteNewFile(workTree: Path, path: String) {
        val file = workTree.resolve(path)

        if (".git" in path.split('/') ||
            !Files.exists(file, LinkOption.NOFOLLOW_LINKS) ||
            Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
        ) {
            return
        }

        // A link in a folder of the path could lead out of the working tree
        val parent = file.parent.toRealPath()

        if (!parent.startsWith(workTree)) {
            return
        }

        Files.delete(file)

        var folder: Path? = parent

        while (folder != null && folder != workTree && folder.startsWith(workTree) && isEmpty(folder)) {
            Files.delete(folder)
            folder = folder.parent
        }
    }

    private fun isEmpty(folder: Path) = Files.newDirectoryStream(folder).use { !it.iterator().hasNext() }
}
