package dev.app.leaf.data.git

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.data.git.signers.gpgSigningError
import dev.app.leaf.data.git.signers.sshSigningError
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX
import org.eclipse.jgit.util.FS_Win32
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class JGit @Inject constructor(
    private val windowsFs: Provider<WindowsFs>,
    private val loginShellEnvironment: LoginShellEnvironment,
) {
    // Concurrent because cleanupExcept iterates it while other tabs open repositories
    private val repositories = ConcurrentHashMap<String, Git>()

    suspend fun <T> provide(
        repositoryPath: String,
        errorHandle: ((Exception) -> GitError)? = null,
        block: suspend EitherContext<GitError>.(Git) -> T,
    ) = either<T, GitError> {
        val cachedGit: Git? = repositories[repositoryPath]

        val git = if (cachedGit == null) {
            val newGit = handleException(
                exceptionMapper = { RepositoryReadError(it.message.orEmpty()) }
            ) {
                open(repositoryPath)
            }.bind()

            repositories[repositoryPath] = newGit
            newGit
        } else {
            cachedGit
        }

        try {
            Either.Ok(block(git))
        } catch (ex: Exception) {
            Either.Err(ex.toGitError(errorHandle))
        }
    }

    suspend fun <T> provideOptional(
        repositoryPath: String?,
        errorHandle: ((Exception) -> GitError)? = null,
        block: suspend EitherContext<GitError>.(Git?) -> T,
    ) = either<T, GitError> {

        val git = if (repositoryPath != null) {
            val cachedGit = repositories[repositoryPath]

            if (cachedGit == null) {
                val newGit = handleException(
                    exceptionMapper = { RepositoryReadError(it.message.orEmpty()) }
                ) {
                    open(repositoryPath)
                }.bind()

                repositories[repositoryPath] = newGit
                newGit
            } else {
                cachedGit
            }
        } else {
            null
        }

        try {
            Either.Ok(block(git))
        } catch (ex: Exception) {
            Either.Err(ex.toGitError(errorHandle))
        }
    }

    /**
     * Like [provide], but the repository isn't cached: it's opened for [block] and closed after it. For a repository
     * that may be removed right after, such as one that is being cloned.
     */
    suspend fun <T> provideOnce(
        repositoryPath: String,
        errorHandle: ((Exception) -> GitError)? = null,
        block: suspend EitherContext<GitError>.(Git) -> T,
    ) = either<T, GitError> {
        val git = handleException(
            exceptionMapper = { RepositoryReadError(it.message.orEmpty()) }
        ) {
            open(repositoryPath)
        }.bind()

        git.use {
            try {
                Either.Ok(block(git))
            } catch (ex: Exception) {
                Either.Err(ex.toGitError(errorHandle))
            }
        }
    }

    /**
     * Signing errors come first: commits, merges, rebases and tags all sign, and an operation's own [errorHandle]
     * doesn't know about them.
     */
    private fun Exception.toGitError(errorHandle: ((Exception) -> GitError)?): GitError {
        return gpgSigningError() ?: sshSigningError() ?: errorHandle?.invoke(this)
            ?: GenericError(message.orEmpty(), this)
    }

    /**
     * Opens a repository with Leaf's file system, which decides how hooks run: through Git Bash on Windows, with the
     * login shell's environment on macOS and Linux. For a linked worktree, it also keeps HEAD's reflog in the
     * worktree's own git dir ([LinkedWorktreeLogs]). [provide] and [provideOptional] both use it, as they share the
     * cache.
     */
    private fun open(repositoryPath: String): Git {
        val gitDir = File(repositoryPath)
        val linkedWorktreeLogs = LinkedWorktreeLogs.of(gitDir)

        val fs = when (val detected = FS.detect()) {
            is FS_Win32 -> windowsFs.get().withLinkedWorktreeLogs(linkedWorktreeLogs)
            is FS_POSIX -> PosixFs(loginShellEnvironment, linkedWorktreeLogs)
            else -> detected
        }

        return Git.open(gitDir, fs)
    }

    fun cleanupExcept(repositoriesToKeep: Set<String>) {
        val repositoriesToRemove = repositories.keys - repositoriesToKeep

        for (repo in repositoriesToRemove) {
            try {
                repositories.remove(repo)?.close()
            } catch (ex: Exception) {
                printError(TAG, "Failed to cleanup $repo from memory: ${ex.message}")
            }
        }
    }
}

