package dev.app.leaf.data.git

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_Win32
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class JGit @Inject constructor(
    private val windowsFs: Provider<WindowsFs>,
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
                val fs = FS.detect()

                val fsToUse = if (fs is FS_Win32) {
                    windowsFs.get()
                } else {
                    fs
                }

                Git
                    .open(File(repositoryPath), fsToUse)
            }.bind()

            repositories[repositoryPath] = newGit
            newGit
        } else {
            cachedGit
        }

        try {
            Either.Ok(block(git))
        } catch (ex: Exception) {
            val error = errorHandle?.invoke(ex) ?: GenericError(ex.message.orEmpty(), ex)
            Either.Err(error)
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
                    Git
                        .open(File(repositoryPath))
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
            val error = errorHandle?.invoke(ex) ?: GenericError(ex.message.orEmpty(), ex)
            Either.Err(error)
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

