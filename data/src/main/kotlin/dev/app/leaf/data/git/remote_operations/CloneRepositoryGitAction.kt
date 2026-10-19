package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.common.printDebug
import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.remote.branchNotFound
import dev.app.leaf.data.git.credentials.CredentialsHandler
import dev.app.leaf.data.git.submodules.InitializeAllSubmodulesGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.interfaces.ICloneRepositoryGitAction
import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File
import javax.inject.Inject

private const val TAG = "CloneRepositoryGitAction"

class CloneRepositoryGitAction @Inject constructor(
    private val handleTransportGitAction: HandleTransportGitAction,
    private val initializeAllSubmodulesGitAction: InitializeAllSubmodulesGitAction,
) : ICloneRepositoryGitAction {
    override operator fun invoke(
        directory: File,
        url: String,
        cloneSubmodules: Boolean,
        branch: String?,
        singleBranch: Boolean,
    ): Flow<CloneState> =
        callbackFlow {
            var lastTitle: String = ""
            var lastTotalWork = 0
            var progress = 0

            try {
                ensureActive()

                trySend(CloneState.Cloning("Starting...", progress, lastTotalWork))

                val chosenBranch = branch?.trim()?.takeIf { it.isNotEmpty() }

                val result = handleTransportGitAction(null) {
                    // JGit clones every branch unless it's given the ref to clone
                    val singleBranchRef = if (singleBranch) singleBranchRef(url, chosenBranch) else null

                    Git.cloneRepository()
                        .setDirectory(directory)
                        .setURI(url)
                        .setNoCheckout(true)
                        .apply {
                            if (singleBranchRef != null) {
                                setBranch(singleBranchRef)
                                setCloneAllBranches(false)
                                setBranchesToClone(listOf(singleBranchRef))
                            } else if (chosenBranch != null) {
                                setBranch(chosenBranch)
                            }
                        }
                        .setProgressMonitor(
                            object : ProgressMonitor {
                                override fun start(totalTasks: Int) {
                                    printDebug(TAG, "ProgressMonitor Start with total tasks of: $totalTasks")
                                }

                                override fun beginTask(title: String?, totalWork: Int) {
                                    println("ProgressMonitor Begin task with title: $title")
                                    lastTitle = title.orEmpty()
                                    lastTotalWork = totalWork
                                    progress = 0
                                    trySend(CloneState.Cloning(lastTitle, progress, lastTotalWork))
                                }

                                override fun update(completed: Int) {
                                    printDebug(TAG, "ProgressMonitor Update $completed")
                                    ensureActive()

                                    progress += completed
                                    trySend(CloneState.Cloning(lastTitle, progress, lastTotalWork))
                                }

                                override fun endTask() {
                                    printDebug(TAG, "ProgressMonitor End task")
                                }

                                override fun isCancelled(): Boolean {
                                    return !isActive
                                }

                                override fun showDuration(enabled: Boolean) {}
                            }
                        )
                        .setTransportConfigCallback { handleTransport(it) }
                        .setCloneSubmodules(cloneSubmodules)
                        .call()
                }

                when (result) {
                    is Either.Err -> {
                        // JGit says it as a plain message, as git does
                        val missingBranch = (result.error as? GenericError)?.message?.let(::branchNotFound)

                        trySend(CloneState.Fail(missingBranch ?: result.error))
                    }

                    is Either.Ok -> {
                        val git = result.value

                        useBuiltinLfs(git.repository) {
                            git.checkout()
                                .setName(git.repository.fullBranch)
                                .setForced(true)
                                .call()
                        }

                        // TODO Test this
                        initializeAllSubmodulesGitAction(git)

                        ensureActive()
                        trySend(CloneState.Completed(directory))
                    }
                }
            } catch (ex: Exception) {
                printError(TAG, ex.localizedMessage, ex)
                if (ex.cause?.cause is CancellationException) {
                    printDebug(TAG, "Clone cancelled")
                } else {
                    trySend(CloneState.Fail(GenericError(ex.localizedMessage)))
                }
            } finally {
                channel.close()
            }

            awaitClose()
        }

    /**
     * The ref that `git clone --single-branch` clones: [branch] as a branch or else as a tag, or the remote's default
     * branch without one. JGit's clone takes only full ref names there, and can't tell which a name is, so one
     * ls-remote asks, with the clone's credentials. A name that the remote lacks fails as JGit's own clone does.
     */
    private fun CredentialsHandler.singleBranchRef(url: String, branch: String?): String? {
        val refs = Git.lsRemoteRepository()
            .setRemote(url)
            .setTransportConfigCallback { handleTransport(it) }
            .callAsMap()

        if (branch == null) {
            return refs[Constants.HEAD]?.target?.name?.takeIf { it.startsWith(Constants.R_HEADS) }
        }

        return listOf(Constants.R_HEADS + branch, Constants.R_TAGS + branch, branch).firstOrNull { it in refs }
            ?: throw TransportException("Remote branch '$branch' not found in upstream origin")
    }
}
