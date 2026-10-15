package dev.app.leaf.data.git

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.remote.missingObjectError
import dev.app.leaf.data.git.config.GitIncludedConfig
import dev.app.leaf.data.git.config.IncludedConfigSource
import dev.app.leaf.data.git.remote_operations.sshNeedsGitError
import dev.app.leaf.data.git.repository.unsupportedIndexError
import dev.app.leaf.data.git.signers.gpgSigningError
import dev.app.leaf.data.git.signers.sshSigningError
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.*
import kotlinx.coroutines.sync.withLock
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
class JGit(
    private val windowsFs: Provider<WindowsFs>,
    private val loginShellEnvironment: LoginShellEnvironment,
    // Fork-only: where git finds hooks, or null to find them as JGit does
    private val hooksDirectory: HooksDirectory? = null,
    // Fork-only: what git reads through includeIf, or null to open repositories as JGit does
    private val includedConfig: IncludedConfigSource? = null,
) {
    @Inject
    constructor(
        windowsFs: Provider<WindowsFs>,
        loginShellEnvironment: LoginShellEnvironment,
        gitHooksDirectory: GitHooksDirectory,
        gitIncludedConfig: GitIncludedConfig,
    ) : this(
        windowsFs,
        loginShellEnvironment,
        gitHooksDirectory as HooksDirectory,
        gitIncludedConfig as IncludedConfigSource,
    )

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

        refreshIncludedConfig(git)

        try {
            Either.Ok(block(git))
        } catch (ex: Exception) {
            Either.Err(ex.toGitError(git, errorHandle))
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

        git?.let { refreshIncludedConfig(it) }

        try {
            Either.Ok(block(git))
        } catch (ex: Exception) {
            Either.Err(ex.toGitError(git, errorHandle))
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
            refreshIncludedConfig(git)

            try {
                Either.Ok(block(git))
            } catch (ex: Exception) {
                Either.Err(ex.toGitError(git, errorHandle))
            }
        }
    }

    /**
     * Signing errors come first: commits, merges, rebases and tags all sign, and an operation's own [errorHandle]
     * doesn't know about them. So does JGit's refusal of SSH remotes, which every remote operation can meet, and so do
     * an index that JGit can't read and an object that a partial clone hasn't downloaded (fork-only), which any
     * operation can meet.
     */
    private fun Exception.toGitError(git: Git?, errorHandle: ((Exception) -> GitError)?): GitError {
        return gpgSigningError() ?: sshSigningError() ?: sshNeedsGitError() ?: unsupportedIndexError(git?.repository)
            ?: missingObjectError() ?: errorHandle?.invoke(this) ?: GenericError(message.orEmpty(), this)
    }

    /**
     * Opens a repository with Leaf's file system, which decides where hooks are, as git finds them ([HooksDirectory]),
     * and how they run: through Git Bash on Windows, with the login shell's environment on macOS and Linux. For a
     * linked worktree, it also keeps HEAD's reflog in the worktree's own git dir ([LinkedWorktreeLogs]). [provide] and
     * [provideOptional] both use it, as they share the cache.
     */
    private fun open(repositoryPath: String): Git {
        val gitDir = File(repositoryPath)
        val linkedWorktreeLogs = LinkedWorktreeLogs.of(gitDir)

        val fs = when (val detected = FS.detect()) {
            is FS_Win32 -> windowsFs.get().withLinkedWorktreeLogs(linkedWorktreeLogs)
            is FS_POSIX -> PosixFs(loginShellEnvironment, linkedWorktreeLogs, hooksDirectory)
            else -> detected
        }

        return if (includedConfig != null) openWithIncludedConfig(gitDir, fs) else Git.open(gitDir, fs)
    }

    /**
     * Fork-only: puts what git reads through `includeIf` under the repository's config ([IncludedConfigRepository]),
     * the first time and whenever one of the files that it depends on changed. Otherwise this only reads their state.
     */
    private suspend fun refreshIncludedConfig(git: Git) {
        val repository = git.repository as? IncludedConfigRepository ?: return
        val source = includedConfig ?: return

        try {
            repository.includedConfigLock.withLock {
                if (repository.isIncludedConfigStale()) {
                    repository.useIncludedConfig(source.read(repository))
                }
            }
        } catch (ex: Exception) {
            printError(TAG, "Reading the config as JGit does, as git's includes couldn't be read: ${ex.message}")
        }
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

