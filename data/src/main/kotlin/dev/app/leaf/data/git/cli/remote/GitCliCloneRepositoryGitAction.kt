// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.remote_operations.useBuiltinLfs
import dev.app.leaf.domain.errors.CloneSubmodulesError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.models.TaskProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val TAG = "GitCliCloneRepository"

private const val SUBMODULES_STAGE = "Cloning submodules"

/**
 * Clones with `git clone --no-checkout`, then checks the files out with JGit and Leaf's built-in LFS, as
 * [dev.app.leaf.data.git.remote_operations.CloneRepositoryGitAction] does after JGit's own clone, so git-lfs isn't
 * needed. With `cloneSubmodules`, `git submodule update --init --recursive` then clones the submodules, as
 * `git clone --recurse-submodules` does.
 *
 * A clone that fails or is cancelled is removed, as git removes it: the folder, or what's in it when it was an empty
 * folder before. git refuses a folder with files in it, which is then left alone. Only a clone whose submodules failed
 * is kept, as with `git clone --recurse-submodules`, since the repository itself is complete.
 */
class GitCliCloneRepositoryGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
) {
    operator fun invoke(directory: File, url: String, cloneSubmodules: Boolean): Flow<CloneState> = channelFlow {
        val destination = directory.absoluteFile
        val existed = destination.exists()
        // Leaf only removes what the clone created
        val removable = !existed || destination.list()?.isEmpty() == true
        var keep = false

        try {
            send(CloneState.Cloning("Starting...", 0, 0))

            val error = try {
                clone(destination, url, cloneSubmodules)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Such as the askpass server's socket failing. The dialog would wait forever for a state otherwise.
                printError(TAG, "Clone failed", e)
                GenericError(e.message.orEmpty(), e)
            }
            keep = error == null || error is CloneSubmodulesError

            send(if (error == null) CloneState.Completed(destination) else CloneState.Fail(error))
        } finally {
            // Also after a cancellation: git removes its folder when it's stopped, but not JGit's checkout
            if (!keep && removable) {
                removePartialClone(destination, existed)
            }
        }
    }

    private suspend fun ProducerScope<CloneState>.clone(
        destination: File,
        url: String,
        cloneSubmodules: Boolean,
    ): GitError? {
        val clone = remoteCommand.run(
            workingDirectory = destination.parentFile ?: destination,
            args = listOf("clone", "--progress", "--no-checkout", "--", url, destination.path),
            onProgress = { reportProgress(it, prefix = "") },
        )

        val output = when (clone) {
            is Either.Err -> return clone.error
            is Either.Ok -> clone.value
        }

        if (output.exitCode != 0) {
            return remoteOperationError(output.exitCode, output.stderr)
        }

        send(CloneState.Cloning("Checking out files", 0, 0))
        checkOut(destination)?.let { return it }

        if (cloneSubmodules && File(destination, Constants.DOT_GIT_MODULES).isFile) {
            send(CloneState.Cloning(SUBMODULES_STAGE, 0, 0))

            val error = when (
                val update = remoteCommand.run(
                    workingDirectory = destination,
                    args = listOf("submodule", "update", "--init", "--recursive", "--progress"),
                    onProgress = { reportProgress(it, prefix = "$SUBMODULES_STAGE: ") },
                )
            ) {
                is Either.Err -> update.error
                is Either.Ok -> if (update.value.exitCode == 0) {
                    null
                } else {
                    remoteOperationError(update.value.exitCode, update.value.stderr)
                }
            }

            if (error != null) {
                return CloneSubmodulesError(destination.path, error)
            }
        }

        return null
    }

    /** Checks out what HEAD points to, as JGit's own clone does, unless the repository has no commits yet. */
    private suspend fun ProducerScope<CloneState>.checkOut(destination: File): GitError? {
        val checkout = withContext(Dispatchers.IO) {
            jgit.provideOnce(File(destination, Constants.DOT_GIT).path) { git ->
                val repository = git.repository

                if (repository.resolve(Constants.HEAD) != null) {
                    useBuiltinLfs(repository) {
                        git.checkout()
                            .setName(repository.fullBranch)
                            .setForced(true)
                            .setProgressMonitor(CheckoutProgress(this@checkOut))
                            .call()
                    }
                }
            }
        }

        // A cancelled checkout stops with an error of its own
        currentCoroutineContext().ensureActive()

        return (checkout as? Either.Err)?.error
    }

    private fun ProducerScope<CloneState>.reportProgress(progress: TaskProgress?, prefix: String) {
        val stage = progress?.stage ?: return

        trySend(CloneState.Cloning(prefix + stage, progress.percent ?: 0, 100))
    }

    /** Removes what the clone left: [destination], or what's in it when it [existed] before, empty. */
    private fun removePartialClone(destination: File, existed: Boolean) {
        val leftovers = if (existed) destination.listFiles().orEmpty().toList() else listOf(destination)

        for (leftover in leftovers) {
            try {
                // JGit's delete doesn't follow symbolic links, which the checkout may have created
                FileUtils.delete(leftover, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
            } catch (e: IOException) {
                printError(TAG, "Couldn't remove ${leftover.path} after the clone stopped", e)
            }
        }
    }
}

/** JGit's checkout progress, as the clone dialog shows it. The checkout stops once the clone is cancelled. */
private class CheckoutProgress(private val scope: ProducerScope<CloneState>) : ProgressMonitor {
    private var title = ""
    private var total = 0
    private var completed = 0
    private var percent = -1

    override fun start(totalTasks: Int) {}

    override fun beginTask(title: String?, totalWork: Int) {
        this.title = title.orEmpty()
        total = totalWork
        completed = 0
        percent = -1
        report()
    }

    override fun update(completed: Int) {
        this.completed += completed
        report()
    }

    override fun endTask() {}

    override fun isCancelled(): Boolean = !scope.isActive

    override fun showDuration(enabled: Boolean) {}

    /** Only when the percentage changes, as JGit reports each file. */
    private fun report() {
        val newPercent = if (total > 0) completed * 100 / total else 0

        if (newPercent != percent) {
            percent = newPercent
            scope.trySend(CloneState.Cloning(title, newPercent, if (total > 0) 100 else 0))
        }
    }
}
