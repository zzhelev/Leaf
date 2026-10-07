package dev.app.leaf.data.git.repository

import dev.app.leaf.common.printError
import dev.app.leaf.common.systemSeparator
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.OpenRepoError
import dev.app.leaf.domain.errors.handleException
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IOpenRepositoryGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.submodule.SubmoduleWalk
import java.io.File
import javax.inject.Inject

private const val TAG = "OpenRepositoryGitAction"

class OpenRepositoryGitAction @Inject constructor() : IOpenRepositoryGitAction {
    override suspend operator fun invoke(directory: String): Either<String, AppError> {
        val directory = File(directory)

        if (!directory.exists()) {
            printError(TAG, "Can't open git repo, specified path does not exist")
            return Either.Err(OpenRepoError.DirectoryNotFoundError)
        }

        if (!directory.isDirectory) {
            printError(TAG, "Can't open git repo, specified path is not a directory")
            return Either.Err(OpenRepoError.PathIsNotDirectory)
        }

        return handleException<String, OpenRepoError>(
            dispatcher = Dispatchers.IO,
            exceptionMapper = { e ->
                printError(TAG, "Can't open git repo", e)
                OpenRepoError.RepositoryLoadFailed(e.message.orEmpty())
            },
        ) {
            val linkedWorktreeGitDir = getLinkedWorktreeGitDir(directory)

            val repository = when {
                linkedWorktreeGitDir != null -> openLinkedWorktree(directory, linkedWorktreeGitDir)
                directory.listFiles()?.any { it.name == ".git" && it.isFile } == true -> openSubmoduleRepository(directory)
                else -> openRepository(directory)
            }

            if (repository == null) {
                printError(TAG, "Can't open git repo, no repository found in the specified path")
                raiseError(OpenRepoError.RepositoryNotFoundInPath)
            }

            repository.use { repository ->
                repository.workTree // test if repository is valid
                repository.directory.absolutePath
            }
        }
    }

    /**
     * Linked worktrees (`git worktree add`) have a `.git` file like submodules, but the git dir it points to
     * (`<common dir>/worktrees/<name>`) contains a `commondir` file.
     */
    private fun getLinkedWorktreeGitDir(directory: File): File? {
        val dotGitFile = File(directory, Constants.DOT_GIT)

        if (!dotGitFile.isFile) {
            return null
        }

        val content = dotGitFile.readText().trim()

        if (!content.startsWith(Constants.GITDIR)) {
            return null
        }

        val gitDir = File(content.removePrefix(Constants.GITDIR).trim())
            .let { if (it.isAbsolute) it else File(directory, it.path) }
            .normalize()

        return gitDir.takeIf { File(it, Constants.COMMONDIR_FILE).isFile }
    }

    private suspend fun openLinkedWorktree(directory: File, gitDir: File): Repository = withContext(Dispatchers.IO) {
        FileRepositoryBuilder()
            .setGitDir(gitDir)
            .setWorkTree(directory)
            .readEnvironment() // scan environment GIT_* variables
            .setMustExist(true)
            .build()
    }

    private suspend fun openRepository(directory: File): Repository = withContext(Dispatchers.IO) {
        val gitDirectory = if (directory.name == ".git") {
            directory
        } else {
            val gitDir = File(directory, ".git")
            if (gitDir.exists() && gitDir.isDirectory) {
                gitDir
            } else
                directory
        }

        val builder = FileRepositoryBuilder()
        return@withContext builder.setGitDir(gitDirectory)
            .readEnvironment() // scan environment GIT_* variables
            .findGitDir() // scan up the file system tree
            .build()
    }

    private suspend fun openSubmoduleRepository(directory: File): Repository? = withContext(Dispatchers.IO) {
        val parent = getRepositoryParent(directory)

        if (parent == null) {
            printError(TAG, "Can't open git repo, submodule's parent repository not found")
            return@withContext null
        }

        val repository = openRepository(parent)

        val submoduleRelativePath =
            directory.absolutePath.removePrefix("${repository.directory.parent}$systemSeparator")

        return@withContext SubmoduleWalk.getSubmoduleRepository(repository, submoduleRelativePath)
    }

    private fun getRepositoryParent(directory: File?): File? {
        if (directory == null) return null

        if (directory.listFiles()?.any { it.name == ".git" && it.isDirectory } == true) {
            return directory
        }

        return getRepositoryParent(directory.parentFile)
    }
}