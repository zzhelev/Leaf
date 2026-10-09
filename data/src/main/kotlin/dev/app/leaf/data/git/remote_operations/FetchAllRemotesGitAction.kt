package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.SshNeedsGitError
import dev.app.leaf.domain.exceptions.FetchException
import dev.app.leaf.domain.interfaces.IFetchAllRemotesGitAction
import dev.app.leaf.domain.models.Remote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteConfig
import javax.inject.Inject

private const val TAG = "FetchAllBranchesGitAction"

class FetchAllRemotesGitAction @Inject constructor(
    private val handleTransportGitAction: HandleTransportGitAction,
    private val jgit: JGit,
) : IFetchAllRemotesGitAction {
    override suspend operator fun invoke(repositoryPath: String, specificRemote: Remote?) = jgit.provide(repositoryPath) { git ->
        val allRemotes = git.remoteList().call()
        val matchingRemote = specificRemote?.let {
            allRemotes.firstOrNull {
                it.name == specificRemote.name
            }
        }
        val remotes = if (matchingRemote != null) {
            listOf(matchingRemote)
        } else {
            allRemotes
        }

        val errors = mutableListOf<Pair<RemoteConfig, GitError>>()
        for (remote in remotes) {
            // It returns JGit's failures as errors, it doesn't throw them
            val result = handleTransportGitAction(repositoryPath) {
                coroutineScope {
                    git
                        .fetch()
                        .setRemote(remote.name)
                        .setRefSpecs(remote.fetchRefSpecs)
                        .setRemoveDeletedRefs(true)
                        .setTransportConfigCallback { handleTransport(it) }
                        .setCredentialsProvider(CredentialsProvider.getDefault())
                        .setProgressMonitor(object : ProgressMonitor {
                            override fun start(totalTasks: Int) {}

                            override fun beginTask(title: String?, totalWork: Int) {}

                            override fun update(completed: Int) {}

                            override fun endTask() {}

                            override fun isCancelled(): Boolean = !isActive

                            override fun showDuration(enabled: Boolean) {}
                        })
                        .call()
                }
            }

            if (result is Either.Err) {
                val exception = (result.error as? GenericError)?.exception
                printError(TAG, "Fetch failed for remote ${remote.name} with error ${result.error}", exception)

                if (exception?.message != "Cancelled authentication" && exception !is CancellationException) {
                    errors.add(remote to result.error)
                }
            }
        }

        if (errors.isNotEmpty()) {
            val errorText = errors.joinToString("\n") {
                "Fetch failed for remote ${it.first.name}: ${it.second.text()}"
            }

            throw FetchException(errorText)
        }
    }
}

private fun GitError.text(): String = when (this) {
    is GenericError -> message
    is SshNeedsGitError -> describe()
    else -> toString()
}
