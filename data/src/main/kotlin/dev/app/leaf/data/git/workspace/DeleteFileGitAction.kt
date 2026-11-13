package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.discards.DiscardStore
import dev.app.leaf.data.git.discards.keepingDiscarded
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.errors.DeleteFileError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDeleteFileGitAction
import dev.app.leaf.domain.interfaces.IDiscardEntriesGitAction
import dev.app.leaf.domain.models.NestedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import javax.inject.Inject

class DeleteFileGitAction @Inject constructor(
    private val jgit: JGit,
    // Fork-only: keeps what is deleted, for Undo (upstream issue 39)
    private val discardStore: DiscardStore,
) : IDeleteFileGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        filePath: String
    ) = jgit.provide(repositoryPath) { git ->
        val fileToDelete = File(git.repository.workTree, filePath)

        // Looked at again here, not taken from the status: the folder may have changed since it was listed
        if (nestedRepositoryOf(git.repository.workTree, git.repository.commonDirectory, filePath) ==
            NestedRepository.LINKED_WORKTREE
        ) {
            raiseError(DeleteFileError.LinkedWorktree(filePath))
        }

        keepingDiscarded(discardStore, repositoryPath, git.repository, DiscardKind.DELETE, emptyList()) { pending ->
            try {
                // Fork-only: moved into the discard's record. A folder that can't be moved there, as into a git dir on
                // another file system, is deleted, and can't be undone.
                if (!withContext(Dispatchers.IO) { pending.moveOut(filePath) }) {
                    // JGit's delete doesn't follow symbolic links. Kotlin's deleteRecursively does, even when called on
                    // the link itself, and would empty the folder that a link points to, possibly outside the
                    // repository.
                    FileUtils.delete(fileToDelete, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
                }

                // Fork-only: a file added with `git add -N` goes from the index too, as with git rm -f (see
                // IntentToAdd)
                git.repository.forgetIntentToAdd(listOf(filePath))
            } catch (e: IOException) {
                raiseError(GenericError("Delete file recursively failed", e))
            }
        }
    }
}
