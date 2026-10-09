package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDeleteFileGitAction
import dev.app.leaf.domain.interfaces.IDiscardEntriesGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import javax.inject.Inject

class DeleteFileGitAction @Inject constructor(
    private val jgit: JGit,
) : IDeleteFileGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        filePath: String
    ) = jgit.provide(repositoryPath) { git ->
        val fileToDelete = File(git.repository.workTree, filePath)

        try {
            // JGit's delete doesn't follow symbolic links. Kotlin's deleteRecursively does, even when called on the
            // link itself, and would empty the folder that a link points to, possibly outside the repository.
            FileUtils.delete(fileToDelete, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
        } catch (e: IOException) {
            raiseError(GenericError("Delete file recursively failed", e))
        }
    }
}
